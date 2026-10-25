mod crypt;
mod hexio;
mod pdfium;
mod rar;

use jni::objects::{JByteArray, JIntArray, JMethodID, JObject, JString, JValue};
use jni::signature::{Primitive, ReturnType};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jobjectArray, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

use sha2::Digest;
use std::fs::File;
use std::io::{self, Read, Write};
use zeroize::Zeroize;

const ALGO_CRC32: i32 = 0;
const ALGO_MD5: i32 = 1;
const ALGO_SHA1: i32 = 2;
const ALGO_SHA256: i32 = 3;

fn hex(bytes: &[u8]) -> String {
    let mut s = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        s.push_str(&format!("{:02x}", b));
    }
    s
}

fn digest_file<D: Digest + Write>(file: &mut File) -> io::Result<String> {
    let mut hasher = D::new();
    io::copy(file, &mut hasher)?;
    Ok(hex(&hasher.finalize()))
}

fn crc32_file(file: &mut File) -> io::Result<String> {
    let mut hasher = crc32fast::Hasher::new();
    let mut buf = [0u8; 65536];
    loop {
        let n = file.read(&mut buf)?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(format!("{:08x}", hasher.finalize()))
}

fn hash_file(path: &str, algo: i32) -> io::Result<String> {
    let mut file = File::open(path)?;
    match algo {
        ALGO_CRC32 => crc32_file(&mut file),
        ALGO_MD5 => digest_file::<md5::Md5>(&mut file),
        ALGO_SHA1 => digest_file::<sha1::Sha1>(&mut file),
        ALGO_SHA256 => digest_file::<sha2::Sha256>(&mut file),
        _ => Err(io::Error::new(io::ErrorKind::InvalidInput, "unknown algorithm")),
    }
}

fn java_string(env: &mut JNIEnv, s: &JString) -> Option<String> {
    env.get_string(s).ok().map(String::from)
}

fn null_bytes() -> jbyteArray {
    std::ptr::null_mut()
}

#[no_mangle]
pub extern "system" fn Java_dev_cluvex_strata_core_nativebridge_NativeBridge_version<'local>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jstring {
    let v = format!("strata-native {}", env!("CARGO_PKG_VERSION"));
    env.new_string(v)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_dev_cluvex_strata_core_nativebridge_NativeBridge_hashFile<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    path: JString<'local>,
    algo: jint,
) -> jstring {
    let out = match java_string(&mut env, &path) {
        Some(p) => hash_file(&p, algo).unwrap_or_default(),
        None => String::new(),
    };
    env.new_string(out)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_dev_cluvex_strata_core_nativebridge_NativeBridge_fileSize<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    path: JString<'local>,
) -> jlong {
    match java_string(&mut env, &path) {
        Some(p) => hexio::file_size(&p).map(|s| s as jlong).unwrap_or(-1),
        None => -1,
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_cluvex_strata_core_nativebridge_NativeBridge_readRange<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    path: JString<'local>,
    offset: jlong,
    length: jint,
) -> jbyteArray {
    if offset < 0 || length < 0 {
        return null_bytes();
    }
    let Some(p) = java_string(&mut env, &path) else {
        return null_bytes();
    };
    match hexio::read_range(&p, offset as u64, length as usize) {
        Ok(bytes) => env
            .byte_array_from_slice(&bytes)
            .map(|a| a.into_raw())
            .unwrap_or_else(|_| null_bytes()),
        Err(_) => null_bytes(),
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_cluvex_strata_core_nativebridge_NativeBridge_indexOf<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    path: JString<'local>,
    pattern: JByteArray<'local>,
    from: jlong,
) -> jlong {
    let Some(p) = java_string(&mut env, &path) else {
        return -2;
    };
    let Ok(pat) = env.convert_byte_array(&pattern) else {
        return -2;
    };
    let from = if from < 0 { 0 } else { from as u64 };
    hexio::index_of(&p, &pat, from).unwrap_or(-2)
}

#[no_mangle]
pub extern "system" fn Java_dev_cluvex_strata_core_nativebridge_NativeBridge_lastIndexOf<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    path: JString<'local>,
    pattern: JByteArray<'local>,
    from: jlong,
) -> jlong {
    if from < 0 {
        return -1;
    }
    let Some(p) = java_string(&mut env, &path) else {
        return -2;
    };
    let Ok(pat) = env.convert_byte_array(&pattern) else {
        return -2;
    };
    hexio::last_index_of(&p, &pat, from as u64).unwrap_or(-2)
}

#[no_mangle]
pub extern "system" fn Java_dev_cluvex_strata_core_nativebridge_NativeBridge_writeBytes<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    path: JString<'local>,
    offset: jlong,
    data: JByteArray<'local>,
) -> jboolean {
    if offset < 0 {
        return JNI_FALSE;
    }
    let Some(p) = java_string(&mut env, &path) else {
        return JNI_FALSE;
    };
    let Ok(bytes) = env.convert_byte_array(&data) else {
        return JNI_FALSE;
    };
    match hexio::write_bytes(&p, offset as u64, &bytes) {
        Ok(()) => JNI_TRUE,
        Err(_) => JNI_FALSE,
    }
}

fn array_prefix(env: &mut JNIEnv, array: &JByteArray, length: jint) -> Option<Vec<u8>> {
    let mut bytes = env.convert_byte_array(array).ok()?;
    if length < 0 || length as usize > bytes.len() {
        bytes.zeroize();
        return None;
    }
    bytes.truncate(length as usize);
    Some(bytes)
}

fn to_java(env: &mut JNIEnv, bytes: &[u8]) -> jbyteArray {
    env.byte_array_from_slice(bytes)
        .map(|a| a.into_raw())
        .unwrap_or_else(|_| null_bytes())
}

#[no_mangle]
pub extern "system" fn Java_dev_cluvex_strata_core_nativebridge_NativeBridge_encDeriveKey<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    password: JByteArray<'local>,
    salt: JByteArray<'local>,
    m_cost_kib: jint,
    t_cost: jint,
    p_cost: jint,
) -> jbyteArray {
    if m_cost_kib < 0 || t_cost < 0 || p_cost < 0 {
        return null_bytes();
    }
    let Ok(mut pw) = env.convert_byte_array(&password) else {
        return null_bytes();
    };
    let salt = env.convert_byte_array(&salt).ok();
    let key = salt.and_then(|s| crypt::derive_key(&pw, &s, m_cost_kib as u32, t_cost as u32, p_cost as u32).ok());
    pw.zeroize();
    match key {
        Some(mut k) => {
            let out = to_java(&mut env, &k);
            k.zeroize();
            out
        }
        None => null_bytes(),
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_cluvex_strata_core_nativebridge_NativeBridge_encSeal<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    key: JByteArray<'local>,
    nonce: JByteArray<'local>,
    aad: JByteArray<'local>,
    data: JByteArray<'local>,
    length: jint,
) -> jbyteArray {
    let (Ok(mut k), Ok(n), Ok(a)) = (env.convert_byte_array(&key), env.convert_byte_array(&nonce), env.convert_byte_array(&aad)) else {
        return null_bytes();
    };
    let Some(mut plain) = array_prefix(&mut env, &data, length) else {
        k.zeroize();
        return null_bytes();
    };
    let sealed = crypt::seal(&k, &n, &a, &plain);
    k.zeroize();
    plain.zeroize();
    match sealed {
        Ok(out) => to_java(&mut env, &out),
        Err(_) => null_bytes(),
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_cluvex_strata_core_nativebridge_NativeBridge_encOpen<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    key: JByteArray<'local>,
    nonce: JByteArray<'local>,
    aad: JByteArray<'local>,
    data: JByteArray<'local>,
    length: jint,
) -> jbyteArray {
    let (Ok(mut k), Ok(n), Ok(a)) = (env.convert_byte_array(&key), env.convert_byte_array(&nonce), env.convert_byte_array(&aad)) else {
        return null_bytes();
    };
    let Some(sealed) = array_prefix(&mut env, &data, length) else {
        k.zeroize();
        return null_bytes();
    };
    let opened = crypt::open(&k, &n, &a, &sealed);
    k.zeroize();
    match opened {
        Ok(mut plain) => {
            let out = to_java(&mut env, &plain);
            plain.zeroize();
            out
        }
        Err(_) => null_bytes(),
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_cluvex_strata_core_nativebridge_NativeBridge_rarList<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    path: JString<'local>,
    password: JString<'local>,
    status: JIntArray<'local>,
) -> jobjectArray {
    let null = std::ptr::null_mut();
    let Some(path) = java_string(&mut env, &path) else { return null };
    let password = if password.is_null() { None } else { java_string(&mut env, &password) };
    let (code, flags, entries) = match rar::list(&path, password.as_deref()) {
        Ok((flags, entries)) => (0, flags, entries),
        Err(code) => (code, 0, Vec::new()),
    };
    let _ = env.set_int_array_region(&status, 0, &[code, flags as jint]);
    if code != 0 {
        return null;
    }
    let Ok(array) = env.new_object_array(entries.len() as jint, "java/lang/String", JObject::null()) else { return null };
    for (i, e) in entries.iter().enumerate() {
        let line = format!("{}\0{}\0{}\0{}\0{}\0{}", e.name, e.flags, e.size, e.packed, e.mtime_ms, e.crc);
        let Ok(js) = env.new_string(line) else { return null };
        if env.set_object_array_element(&array, i as jint, &js).is_err() {
            return null;
        }
        let _ = env.delete_local_ref(js);
    }
    array.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_dev_cluvex_strata_core_nativebridge_NativeBridge_rarExtract<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    path: JString<'local>,
    password: JString<'local>,
    sink: JObject<'local>,
) -> jint {
    let Some(path) = java_string(&mut env, &path) else { return rar::BAD_PATH };
    let password = if password.is_null() { None } else { java_string(&mut env, &password) };
    let mut jni_sink = match JniSink::new(&mut env, &sink) {
        Some(s) => s,
        None => return rar::SINK_ERROR,
    };
    rar::extract(&path, password.as_deref(), &mut jni_sink)
}

struct JniSink<'a, 'local> {
    env: &'a mut JNIEnv<'local>,
    sink: &'a JObject<'local>,
    begin: JMethodID,
    write: JMethodID,
    end: JMethodID,
    buffer: JByteArray<'local>,
}

const RAR_CHUNK: usize = 256 * 1024;

impl<'a, 'local> JniSink<'a, 'local> {
    fn new(env: &'a mut JNIEnv<'local>, sink: &'a JObject<'local>) -> Option<Self> {
        let class = env.get_object_class(sink).ok()?;
        let begin = env.get_method_id(&class, "begin", "(Ljava/lang/String;JI)Z").ok()?;
        let write = env.get_method_id(&class, "write", "([BI)Z").ok()?;
        let end = env.get_method_id(&class, "end", "(Z)V").ok()?;
        let buffer = env.new_byte_array(RAR_CHUNK as jint).ok()?;
        Some(JniSink { env, sink, begin, write, end, buffer })
    }

    fn failed(&mut self) -> bool {
        self.env.exception_check().unwrap_or(true)
    }
}

impl rar::Sink for JniSink<'_, '_> {
    fn begin(&mut self, h: &rar::Header) -> Result<bool, ()> {
        let name = self.env.new_string(&h.name).map_err(|_| ())?;
        let r = unsafe {
            self.env.call_method_unchecked(
                self.sink,
                self.begin,
                ReturnType::Primitive(Primitive::Boolean),
                &[JValue::Object(&name).as_jni(), JValue::Long(h.size as jlong).as_jni(), JValue::Int(h.flags as jint).as_jni()],
            )
        };
        let _ = self.env.delete_local_ref(name);
        if self.failed() {
            return Err(());
        }
        r.and_then(|v| v.z()).map_err(|_| ())
    }

    fn data(&mut self, bytes: &[u8]) -> Result<bool, ()> {
        for chunk in bytes.chunks(RAR_CHUNK) {
            let signed = unsafe { std::slice::from_raw_parts(chunk.as_ptr() as *const i8, chunk.len()) };
            self.env.set_byte_array_region(&self.buffer, 0, signed).map_err(|_| ())?;
            let r = unsafe {
                self.env.call_method_unchecked(
                    self.sink,
                    self.write,
                    ReturnType::Primitive(Primitive::Boolean),
                    &[JValue::Object(&self.buffer).as_jni(), JValue::Int(chunk.len() as jint).as_jni()],
                )
            };
            if self.failed() {
                return Err(());
            }
            if !r.and_then(|v| v.z()).map_err(|_| ())? {
                return Ok(false);
            }
        }
        Ok(true)
    }

    fn end(&mut self, ok: bool) -> Result<(), ()> {
        let _ = unsafe {
            self.env.call_method_unchecked(
                self.sink,
                self.end,
                ReturnType::Primitive(Primitive::Void),
                &[JValue::Bool(if ok { JNI_TRUE } else { JNI_FALSE }).as_jni()],
            )
        };
        if self.failed() { Err(()) } else { Ok(()) }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU32, Ordering};

    static COUNTER: AtomicU32 = AtomicU32::new(0);

    fn tmp_file(bytes: &[u8]) -> std::path::PathBuf {
        let mut p = std::env::temp_dir();
        let id = COUNTER.fetch_add(1, Ordering::SeqCst);
        p.push(format!("strata_native_test_{}_{}.bin", std::process::id(), id));
        let mut f = File::create(&p).unwrap();
        f.write_all(bytes).unwrap();
        p
    }

    #[test]
    fn crc32_known_vector() {
        let p = tmp_file(b"123456789");
        assert_eq!(hash_file(p.to_str().unwrap(), ALGO_CRC32).unwrap(), "cbf43926");
        let _ = std::fs::remove_file(p);
    }

    #[test]
    fn md5_empty() {
        let p = tmp_file(b"");
        assert_eq!(
            hash_file(p.to_str().unwrap(), ALGO_MD5).unwrap(),
            "d41d8cd98f00b204e9800998ecf8427e"
        );
        let _ = std::fs::remove_file(p);
    }

    #[test]
    fn sha1_abc() {
        let p = tmp_file(b"abc");
        assert_eq!(
            hash_file(p.to_str().unwrap(), ALGO_SHA1).unwrap(),
            "a9993e364706816aba3e25717850c26c9cd0d89d"
        );
        let _ = std::fs::remove_file(p);
    }

    #[test]
    fn sha256_abc() {
        let p = tmp_file(b"abc");
        assert_eq!(
            hash_file(p.to_str().unwrap(), ALGO_SHA256).unwrap(),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
        let _ = std::fs::remove_file(p);
    }

    #[test]
    fn unknown_algo_errors() {
        let p = tmp_file(b"x");
        assert!(hash_file(p.to_str().unwrap(), 99).is_err());
        let _ = std::fs::remove_file(p);
    }
}
