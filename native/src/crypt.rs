use argon2::{Algorithm, Argon2, Params, Version};
use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use chacha20poly1305::{Key, XChaCha20Poly1305, XNonce};
use zeroize::Zeroize;

pub const KEY_LEN: usize = 32;
pub const NONCE_LEN: usize = 24;
pub const TAG_LEN: usize = 16;
pub const MIN_SALT_LEN: usize = 8;

pub const MAX_M_COST_KIB: u32 = 1 << 18;
pub const MAX_T_COST: u32 = 64;
pub const MAX_P_COST: u32 = 16;

#[derive(Debug, PartialEq, Eq)]
pub enum CryptError {
    BadParams,
    BadLength,
    Auth,
}

pub fn derive_key(password: &[u8], salt: &[u8], m_cost_kib: u32, t_cost: u32, p_cost: u32) -> Result<[u8; KEY_LEN], CryptError> {
    if salt.len() < MIN_SALT_LEN
        || m_cost_kib > MAX_M_COST_KIB
        || t_cost == 0
        || t_cost > MAX_T_COST
        || p_cost == 0
        || p_cost > MAX_P_COST
    {
        return Err(CryptError::BadParams);
    }
    let params = Params::new(m_cost_kib, t_cost, p_cost, Some(KEY_LEN)).map_err(|_| CryptError::BadParams)?;
    let mut out = [0u8; KEY_LEN];
    Argon2::new(Algorithm::Argon2id, Version::V0x13, params)
        .hash_password_into(password, salt, &mut out)
        .map_err(|_| CryptError::BadParams)?;
    Ok(out)
}

pub fn seal(key: &[u8], nonce: &[u8], aad: &[u8], plain: &[u8]) -> Result<Vec<u8>, CryptError> {
    let cipher = cipher(key)?;
    let nonce = nonce_of(nonce)?;
    cipher.encrypt(&nonce, Payload { msg: plain, aad }).map_err(|_| CryptError::BadLength)
}

pub fn open(key: &[u8], nonce: &[u8], aad: &[u8], sealed: &[u8]) -> Result<Vec<u8>, CryptError> {
    if sealed.len() < TAG_LEN {
        return Err(CryptError::BadLength);
    }
    let cipher = cipher(key)?;
    let nonce = nonce_of(nonce)?;
    cipher.decrypt(&nonce, Payload { msg: sealed, aad }).map_err(|_| CryptError::Auth)
}

fn nonce_of(nonce: &[u8]) -> Result<XNonce, CryptError> {
    if nonce.len() != NONCE_LEN {
        return Err(CryptError::BadLength);
    }
    XNonce::try_from(nonce).map_err(|_| CryptError::BadLength)
}

fn cipher(key: &[u8]) -> Result<XChaCha20Poly1305, CryptError> {
    let mut k: [u8; KEY_LEN] = key.try_into().map_err(|_| CryptError::BadLength)?;
    let cipher = XChaCha20Poly1305::new(&Key::from(k));
    k.zeroize();
    Ok(cipher)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hex(s: &str) -> Vec<u8> {
        (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap()).collect()
    }

    fn range(from: u8, to_exclusive: u8) -> Vec<u8> {
        (from..to_exclusive).collect()
    }

    #[test]
    fn argon2id_matches_libsodium() {
        let salt = range(0, 16);
        assert_eq!(
            derive_key(b"correct horse battery", &salt, 4096, 3, 1).unwrap().to_vec(),
            hex("9902b53d23e1756a5b9149b4999d8d6a59953dd9572b838553f2717bd41f573d")
        );
        assert_eq!(
            derive_key(b"", &[0xff; 16], 64, 1, 1).unwrap().to_vec(),
            hex("5bca8c5d1093b9fcf8f71d8e42972b16c14d32b5e36dcda2dca1708d215ce746")
        );
        assert_eq!(
            derive_key("pässwörd".as_bytes(), &salt, 1024, 2, 1).unwrap().to_vec(),
            hex("c7c3d525e294e9544329f8cc8ae5017e8785366de771146b5f2e4071a1f7e259")
        );
    }

    #[test]
    fn kdf_parameters_are_bounded() {
        let salt = [7u8; 16];
        assert_eq!(derive_key(b"x", &salt[..7], 64, 1, 1), Err(CryptError::BadParams));
        assert_eq!(derive_key(b"x", &salt, MAX_M_COST_KIB + 1, 1, 1), Err(CryptError::BadParams));
        assert_eq!(derive_key(b"x", &salt, 64, 0, 1), Err(CryptError::BadParams));
        assert_eq!(derive_key(b"x", &salt, 64, MAX_T_COST + 1, 1), Err(CryptError::BadParams));
        assert_eq!(derive_key(b"x", &salt, 64, 1, 0), Err(CryptError::BadParams));
        assert_eq!(derive_key(b"x", &salt, 64, 1, MAX_P_COST + 1), Err(CryptError::BadParams));
        assert_eq!(derive_key(b"x", &salt, 7, 1, 1), Err(CryptError::BadParams));
    }

    const SUNSCREEN: &[u8] = b"Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.";
    const SUNSCREEN_SEALED: &str = "bd6d179d3e83d43b9576579493c0e939572a1700252bfaccbed2902c21396cbb731c7f1b0b4aa6440bf3a82f4eda7e39ae64c6708c54c216cb96b72e1213b4522f8c9ba40db5d945b11b69b982c1bb9e3f3fac2bc369488f76b2383565d3fff921f9664c97637da9768812f615c68b13b52ec0875924c1c7987947deafd8780acf49";

    #[test]
    fn xchacha20poly1305_matches_the_published_vector() {
        let key = range(0x80, 0xa0);
        let nonce = range(0x40, 0x58);
        let aad = hex("50515253c0c1c2c3c4c5c6c7");
        let sealed = seal(&key, &nonce, &aad, SUNSCREEN).unwrap();
        assert_eq!(sealed, hex(SUNSCREEN_SEALED));
        assert_eq!(open(&key, &nonce, &aad, &sealed).unwrap(), SUNSCREEN);
        assert_eq!(seal(&key, &nonce, b"", b"").unwrap(), hex("1dac8f73146d1e9da796cb7f7221a5df"));
    }

    #[test]
    fn any_change_fails_authentication() {
        let key = range(0x80, 0xa0);
        let nonce = range(0x40, 0x58);
        let aad = hex("50515253c0c1c2c3c4c5c6c7");
        let sealed = hex(SUNSCREEN_SEALED);
        for i in [0, 57, sealed.len() - 1] {
            let mut bad = sealed.clone();
            bad[i] ^= 1;
            assert_eq!(open(&key, &nonce, &aad, &bad), Err(CryptError::Auth), "flipped byte {i}");
        }
        let mut wrong_key = key.clone();
        wrong_key[0] ^= 1;
        assert_eq!(open(&wrong_key, &nonce, &aad, &sealed), Err(CryptError::Auth));
        let mut wrong_nonce = nonce.clone();
        wrong_nonce[23] ^= 1;
        assert_eq!(open(&key, &wrong_nonce, &aad, &sealed), Err(CryptError::Auth));
        assert_eq!(open(&key, &nonce, b"other aad", &sealed), Err(CryptError::Auth));
        assert_eq!(open(&key, &nonce, &aad, &sealed[..sealed.len() - 1]), Err(CryptError::Auth));
    }

    #[test]
    fn wrong_lengths_are_rejected() {
        let key = [1u8; 32];
        let nonce = [2u8; 24];
        assert_eq!(seal(&key[..31], &nonce, b"", b"x"), Err(CryptError::BadLength));
        assert_eq!(seal(&key, &nonce[..12], b"", b"x"), Err(CryptError::BadLength));
        assert_eq!(open(&key, &nonce, b"", &[0u8; 15]), Err(CryptError::BadLength));
    }
}
