use memchr::memmem;
use std::fs::{File, OpenOptions};
use std::io::{self, Read, Seek, SeekFrom, Write};

pub const DEFAULT_CHUNK: usize = 1 << 20;

pub fn file_size(path: &str) -> io::Result<u64> {
    Ok(std::fs::metadata(path)?.len())
}

fn read_fully(file: &mut File, buf: &mut [u8]) -> io::Result<usize> {
    let mut total = 0;
    while total < buf.len() {
        let n = file.read(&mut buf[total..])?;
        if n == 0 {
            break;
        }
        total += n;
    }
    Ok(total)
}

pub fn read_range(path: &str, offset: u64, len: usize) -> io::Result<Vec<u8>> {
    let mut file = File::open(path)?;
    let size = file.metadata()?.len();
    if offset >= size || len == 0 {
        return Ok(Vec::new());
    }
    let take = std::cmp::min(len as u64, size - offset) as usize;
    let mut buf = vec![0u8; take];
    file.seek(SeekFrom::Start(offset))?;
    let n = read_fully(&mut file, &mut buf)?;
    buf.truncate(n);
    Ok(buf)
}

pub fn index_of(path: &str, pattern: &[u8], from: u64) -> io::Result<i64> {
    index_of_chunked(path, pattern, from, DEFAULT_CHUNK)
}

pub fn index_of_chunked(path: &str, pattern: &[u8], from: u64, chunk: usize) -> io::Result<i64> {
    if pattern.is_empty() {
        return Ok(-1);
    }
    let mut file = File::open(path)?;
    let size = file.metadata()?.len();
    let plen = pattern.len() as u64;
    if from >= size || size - from < plen {
        return Ok(-1);
    }
    let finder = memmem::Finder::new(pattern);
    let overlap = pattern.len() - 1;
    let chunk = chunk.max(1);
    let mut buf = vec![0u8; chunk + overlap];
    let mut pos = from;
    loop {
        file.seek(SeekFrom::Start(pos))?;
        let n = read_fully(&mut file, &mut buf)?;
        if n < pattern.len() {
            return Ok(-1);
        }
        if let Some(i) = finder.find(&buf[..n]) {
            return Ok((pos + i as u64) as i64);
        }
        if pos + n as u64 >= size {
            return Ok(-1);
        }
        pos += (n - overlap) as u64;
    }
}

pub fn last_index_of(path: &str, pattern: &[u8], from: u64) -> io::Result<i64> {
    last_index_of_chunked(path, pattern, from, DEFAULT_CHUNK)
}

pub fn last_index_of_chunked(path: &str, pattern: &[u8], from: u64, chunk: usize) -> io::Result<i64> {
    if pattern.is_empty() {
        return Ok(-1);
    }
    let mut file = File::open(path)?;
    let size = file.metadata()?.len();
    let plen = pattern.len() as u64;
    if size < plen {
        return Ok(-1);
    }
    let max_start = std::cmp::min(from, size - plen);
    let finder = memmem::FinderRev::new(pattern);
    let overlap = plen - 1;
    let chunk = chunk.max(1) as u64;
    let mut end = max_start + plen;
    let mut buf = vec![0u8; (chunk + overlap) as usize];
    loop {
        let start = end.saturating_sub(chunk + overlap);
        let len = (end - start) as usize;
        file.seek(SeekFrom::Start(start))?;
        let n = read_fully(&mut file, &mut buf[..len])?;
        if let Some(i) = finder.rfind(&buf[..n]) {
            return Ok((start + i as u64) as i64);
        }
        if start == 0 {
            return Ok(-1);
        }
        end = start + overlap;
    }
}

pub fn write_bytes(path: &str, offset: u64, data: &[u8]) -> io::Result<()> {
    let mut file = OpenOptions::new().write(true).open(path)?;
    let size = file.metadata()?.len();
    let end = offset
        .checked_add(data.len() as u64)
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "offset overflow"))?;
    if end > size {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "write past end of file"));
    }
    file.seek(SeekFrom::Start(offset))?;
    file.write_all(data)?;
    file.sync_data()?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicU32, Ordering};

    static COUNTER: AtomicU32 = AtomicU32::new(0);

    struct TempFile(PathBuf);
    impl TempFile {
        fn new(bytes: &[u8]) -> Self {
            let mut p = std::env::temp_dir();
            let id = COUNTER.fetch_add(1, Ordering::SeqCst);
            p.push(format!("strata_hexio_{}_{}.bin", std::process::id(), id));
            std::fs::write(&p, bytes).unwrap();
            TempFile(p)
        }
        fn path(&self) -> &str {
            self.0.to_str().unwrap()
        }
    }
    impl Drop for TempFile {
        fn drop(&mut self) {
            let _ = std::fs::remove_file(&self.0);
        }
    }

    fn prng_bytes(len: usize, seed: u32, alphabet: u8) -> Vec<u8> {
        let mut x = seed.max(1);
        (0..len)
            .map(|_| {
                x ^= x << 13;
                x ^= x >> 17;
                x ^= x << 5;
                (x % alphabet as u32) as u8
            })
            .collect()
    }

    fn naive_index(data: &[u8], pat: &[u8], from: usize) -> i64 {
        if pat.is_empty() || data.len() < pat.len() {
            return -1;
        }
        for i in from..=data.len() - pat.len() {
            if &data[i..i + pat.len()] == pat {
                return i as i64;
            }
        }
        -1
    }

    fn naive_last_index(data: &[u8], pat: &[u8], from: usize) -> i64 {
        if pat.is_empty() || data.len() < pat.len() {
            return -1;
        }
        let max_start = std::cmp::min(from, data.len() - pat.len());
        let mut i = max_start as i64;
        while i >= 0 {
            let s = i as usize;
            if &data[s..s + pat.len()] == pat {
                return i;
            }
            i -= 1;
        }
        -1
    }

    #[test]
    fn size_and_read_range() {
        let t = TempFile::new(b"0123456789");
        assert_eq!(file_size(t.path()).unwrap(), 10);
        assert_eq!(read_range(t.path(), 2, 3).unwrap(), b"234");
        assert_eq!(read_range(t.path(), 8, 10).unwrap(), b"89");
        assert!(read_range(t.path(), 10, 4).unwrap().is_empty());
        assert!(read_range(t.path(), 99, 4).unwrap().is_empty());
        assert!(read_range(t.path(), 0, 0).unwrap().is_empty());
    }

    #[test]
    fn search_edges() {
        let t = TempFile::new(b"abcabcXYZ");
        assert_eq!(index_of(t.path(), b"abc", 0).unwrap(), 0);
        assert_eq!(index_of(t.path(), b"abc", 1).unwrap(), 3);
        assert_eq!(index_of(t.path(), b"XYZ", 0).unwrap(), 6);
        assert_eq!(index_of(t.path(), b"nope", 0).unwrap(), -1);
        assert_eq!(index_of(t.path(), b"", 0).unwrap(), -1);
        assert_eq!(index_of(t.path(), b"abc", 100).unwrap(), -1);
        assert_eq!(last_index_of(t.path(), b"abc", 100).unwrap(), 3);
        assert_eq!(last_index_of(t.path(), b"abc", 2).unwrap(), 0);
        assert_eq!(last_index_of(t.path(), b"XYZ", 5).unwrap(), -1);
        assert_eq!(last_index_of(t.path(), b"toolongpattern!", 0).unwrap(), -1);
    }

    #[test]
    fn chunked_search_matches_naive() {
        for seed in 1..=6u32 {
            let data = prng_bytes(257, seed * 7919, 3);
            let t = TempFile::new(&data);
            for plen in 1..=5usize {
                for pstart in [0usize, 11, 64, 128, 200] {
                    if pstart + plen > data.len() {
                        continue;
                    }
                    let pat = data[pstart..pstart + plen].to_vec();
                    for chunk in [1usize, 2, 3, 5, 8, 31, 64, 300] {
                        for from in [0usize, 1, 50, 127, 200, 256] {
                            let got = index_of_chunked(t.path(), &pat, from as u64, chunk).unwrap();
                            assert_eq!(got, naive_index(&data, &pat, from), "fwd seed={seed} plen={plen} chunk={chunk} from={from}");
                            let got = last_index_of_chunked(t.path(), &pat, from as u64, chunk).unwrap();
                            assert_eq!(got, naive_last_index(&data, &pat, from), "rev seed={seed} plen={plen} chunk={chunk} from={from}");
                        }
                    }
                }
            }
        }
    }

    #[test]
    fn write_in_place_and_reject_past_eof() {
        let t = TempFile::new(b"hello world");
        write_bytes(t.path(), 6, b"WORLD").unwrap();
        assert_eq!(std::fs::read(t.path()).unwrap(), b"hello WORLD");
        assert!(write_bytes(t.path(), 8, b"xyzw").is_err());
        assert_eq!(std::fs::read(t.path()).unwrap(), b"hello WORLD");
        assert_eq!(file_size(t.path()).unwrap(), 11);
    }
}
