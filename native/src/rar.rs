use std::ffi::CString;
use std::os::raw::c_int;
use std::sync::Mutex;

use libc::wchar_t;
use unrar_sys as sys;

static LOCK: Mutex<()> = Mutex::new(());

pub const CANCELLED: i32 = 100;
pub const SINK_ERROR: i32 = 101;
pub const BAD_PATH: i32 = 102;

pub const FLAG_DIRECTORY: u32 = sys::RHDF_DIRECTORY;
pub const FLAG_LINK: u32 = 0x1000;

#[derive(Debug, Clone, PartialEq)]
pub struct Header {
    pub name: String,
    pub flags: u32,
    pub size: u64,
    pub packed: u64,
    pub mtime_ms: i64,
    pub crc: u32,
}

impl Header {
    pub fn is_dir(&self) -> bool {
        self.flags & FLAG_DIRECTORY != 0
    }
    pub fn is_link(&self) -> bool {
        self.flags & FLAG_LINK != 0
    }
}

pub trait Sink {
    fn begin(&mut self, header: &Header) -> Result<bool, ()>;
    fn data(&mut self, bytes: &[u8]) -> Result<bool, ()>;
    fn end(&mut self, ok: bool) -> Result<(), ()>;
}

struct Ctx<'a> {
    password: Option<Vec<wchar_t>>,
    sink: Option<*mut (dyn Sink + 'a)>,
    stop: i32,
}

impl Ctx<'_> {
    fn new(password: Option<&str>) -> Self {
        Ctx { password: password.map(|p| p.chars().map(|c| c as wchar_t).collect()), sink: None, stop: 0 }
    }
}

extern "C" fn callback(msg: sys::UINT, user: sys::LPARAM, p1: sys::LPARAM, p2: sys::LPARAM) -> c_int {
    let ctx = unsafe { &mut *(user as *mut Ctx) };
    match msg {
        sys::UCM_NEEDPASSWORDW => {
            let Some(pw) = ctx.password.as_ref() else { return -1 };
            let cap = p2 as usize;
            if p1 == 0 || pw.len() + 1 > cap {
                return -1;
            }
            unsafe {
                let buf = p1 as *mut wchar_t;
                std::ptr::copy_nonoverlapping(pw.as_ptr(), buf, pw.len());
                *buf.add(pw.len()) = 0;
            }
            1
        }
        sys::UCM_NEEDPASSWORD => -1,
        sys::UCM_CHANGEVOLUMEW | sys::UCM_CHANGEVOLUME => {
            if p2 == sys::RAR_VOL_NOTIFY {
                1
            } else {
                -1
            }
        }
        sys::UCM_PROCESSDATA => {
            let Some(sink) = ctx.sink else { return 1 };
            if p1 == 0 || p2 <= 0 {
                return 1;
            }
            let bytes = unsafe { std::slice::from_raw_parts(p1 as *const u8, p2 as usize) };
            match unsafe { (*sink).data(bytes) } {
                Ok(true) => 1,
                Ok(false) => {
                    ctx.stop = CANCELLED;
                    -1
                }
                Err(()) => {
                    ctx.stop = SINK_ERROR;
                    -1
                }
            }
        }
        _ => 0,
    }
}

fn wide(s: &str) -> Vec<wchar_t> {
    s.chars().map(|c| c as wchar_t).chain(std::iter::once(0)).collect()
}

fn from_wide(w: &[wchar_t]) -> String {
    w.iter()
        .take_while(|&&c| c != 0)
        .map(|&c| char::from_u32(c as u32).unwrap_or('\u{FFFD}'))
        .collect()
}

struct Archive(*const sys::Handle);

impl Drop for Archive {
    fn drop(&mut self) {
        unsafe { sys::RARCloseArchive(self.0) };
    }
}

fn open(path: &str, mode: u32, ctx: *mut Ctx) -> Result<(Archive, u32), i32> {
    let narrow = CString::new(path).map_err(|_| BAD_PATH)?;
    let name_w = wide(path);
    let mut data = sys::OpenArchiveDataEx {
        archive_name: narrow.as_ptr(),
        archive_name_w: name_w.as_ptr(),
        open_mode: mode,
        open_result: 0,
        comment_buffer: std::ptr::null_mut(),
        comment_buffer_size: 0,
        comment_size: 0,
        comment_state: 0,
        flags: 0,
        callback: Some(callback),
        user_data: ctx as sys::LPARAM,
        op_flags: 0,
        comment_buffer_w: std::ptr::null_mut(),
        reserved: [0; 25],
    };
    let handle = unsafe { sys::RAROpenArchiveEx(&mut data as *mut sys::OpenArchiveDataEx) };
    if handle.is_null() || data.open_result != 0 {
        if !handle.is_null() {
            drop(Archive(handle));
        }
        return Err(if data.open_result != 0 { data.open_result as i32 } else { sys::ERAR_EOPEN });
    }
    Ok((Archive(handle), data.flags))
}

fn new_header() -> Box<sys::HeaderDataEx> {
    Box::new(unsafe { std::mem::zeroed() })
}

const HOST_UNIX: u32 = 3;
const S_IFMT: u32 = 0o170000;
const S_IFLNK: u32 = 0o120000;

fn header(h: &sys::HeaderDataEx) -> Header {
    let mut flags = h.flags & (sys::RHDF_ENCRYPTED | sys::RHDF_DIRECTORY | sys::RHDF_SOLID | sys::RHDF_SPLITBEFORE | sys::RHDF_SPLITAFTER);
    if h.redir_type != 0 || (h.host_os == HOST_UNIX && h.file_attr & S_IFMT == S_IFLNK) {
        flags |= FLAG_LINK;
    }
    let filetime = (h.mtime_high as u64) << 32 | h.mtime_low as u64;
    let mtime_ms = if filetime != 0 {
        (filetime / 10_000) as i64 - 11_644_473_600_000
    } else {
        dos_time_ms(h.file_time)
    };
    Header {
        name: from_wide(&h.filename_w),
        flags,
        size: (h.unp_size_high as u64) << 32 | h.unp_size as u64,
        packed: (h.pack_size_high as u64) << 32 | h.pack_size as u64,
        mtime_ms,
        crc: h.file_crc,
    }
}

fn dos_time_ms(t: u32) -> i64 {
    if t == 0 {
        return 0;
    }
    let (sec, min, hour) = ((t & 0x1F) * 2, (t >> 5) & 0x3F, (t >> 11) & 0x1F);
    let (day, month, year) = ((t >> 16) & 0x1F, (t >> 21) & 0x0F, ((t >> 25) & 0x7F) + 1980);
    days_from_civil(year as i64, month.max(1) as i64, day.max(1) as i64) * 86_400_000
        + (hour as i64 * 3600 + min as i64 * 60 + sec as i64) * 1000
}

fn days_from_civil(y: i64, m: i64, d: i64) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = y - era * 400;
    let doy = (153 * (if m > 2 { m - 3 } else { m + 9 }) + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

pub fn list(path: &str, password: Option<&str>) -> Result<(u32, Vec<Header>), i32> {
    let _guard = LOCK.lock().unwrap_or_else(|e| e.into_inner());
    let mut ctx = Ctx::new(password);
    let (archive, flags) = open(path, sys::RAR_OM_LIST, &mut ctx)?;
    let mut out = Vec::new();
    let mut hd = new_header();
    loop {
        let r = unsafe { sys::RARReadHeaderEx(archive.0, &mut *hd as *mut sys::HeaderDataEx) };
        if r == sys::ERAR_END_ARCHIVE {
            return Ok((flags, out));
        }
        if r != sys::ERAR_SUCCESS {
            return Err(r);
        }
        out.push(header(&hd));
        let r = unsafe { sys::RARProcessFileW(archive.0, sys::RAR_SKIP, std::ptr::null(), std::ptr::null()) };
        if r != sys::ERAR_SUCCESS {
            return Err(r);
        }
    }
}

pub fn extract(path: &str, password: Option<&str>, sink: &mut dyn Sink) -> i32 {
    let _guard = LOCK.lock().unwrap_or_else(|e| e.into_inner());
    let mut ctx = Ctx::new(password);
    let ctx_ptr: *mut Ctx = &mut ctx;
    let (archive, _) = match open(path, sys::RAR_OM_EXTRACT, ctx_ptr) {
        Ok(a) => a,
        Err(e) => return e,
    };
    let mut hd = new_header();
    loop {
        let r = unsafe { sys::RARReadHeaderEx(archive.0, &mut *hd as *mut sys::HeaderDataEx) };
        if r == sys::ERAR_END_ARCHIVE {
            return 0;
        }
        if r != sys::ERAR_SUCCESS {
            return r;
        }
        let h = header(&hd);
        let want = if h.is_dir() || h.is_link() {
            false
        } else {
            match sink.begin(&h) {
                Ok(w) => w,
                Err(()) => return SINK_ERROR,
            }
        };
        unsafe { (*ctx_ptr).sink = if want { Some(&mut *sink as *mut (dyn Sink + '_)) } else { None } };
        let op = if want { sys::RAR_TEST } else { sys::RAR_SKIP };
        let r = unsafe { sys::RARProcessFileW(archive.0, op, std::ptr::null(), std::ptr::null()) };
        let stop = unsafe {
            (*ctx_ptr).sink = None;
            (*ctx_ptr).stop
        };
        if want {
            if stop == SINK_ERROR {
                return SINK_ERROR;
            }
            if sink.end(r == sys::ERAR_SUCCESS && stop == 0).is_err() {
                return SINK_ERROR;
            }
        }
        if stop != 0 {
            return stop;
        }
        if r != sys::ERAR_SUCCESS {
            return r;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    fn fixture(name: &str) -> String {
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../app/src/test/resources/rar").join(name).to_string_lossy().into_owned()
    }

    struct Collect {
        files: Vec<(String, Vec<u8>, bool)>,
        current: Option<(String, Vec<u8>)>,
        only: Option<String>,
        stop_after: Option<usize>,
    }

    impl Collect {
        fn new() -> Self {
            Collect { files: vec![], current: None, only: None, stop_after: None }
        }
    }

    impl Sink for Collect {
        fn begin(&mut self, h: &Header) -> Result<bool, ()> {
            if self.only.as_deref().is_some_and(|o| o != h.name) {
                return Ok(false);
            }
            self.current = Some((h.name.clone(), vec![]));
            Ok(true)
        }
        fn data(&mut self, bytes: &[u8]) -> Result<bool, ()> {
            let cur = self.current.as_mut().unwrap();
            cur.1.extend_from_slice(bytes);
            Ok(self.stop_after.map_or(true, |n| cur.1.len() < n))
        }
        fn end(&mut self, ok: bool) -> Result<(), ()> {
            let (name, data) = self.current.take().unwrap();
            self.files.push((name, data, ok));
            Ok(())
        }
    }

    #[test]
    fn lists_and_extracts_rar4_and_rar5() {
        let (_, entries) = list(&fixture("test_read_format_rar.rar"), None).unwrap();
        let names: Vec<_> = entries.iter().map(|e| e.name.as_str()).collect();
        assert!(names.contains(&"test.txt") && names.contains(&"testdir/test.txt"), "{names:?}");
        assert!(entries.iter().any(|e| e.name == "testlink" && e.is_link()));
        assert!(entries.iter().any(|e| e.name == "testemptydir" && e.is_dir()));

        let mut c = Collect::new();
        assert_eq!(extract(&fixture("test_read_format_rar.rar"), None, &mut c), 0);
        let txt = c.files.iter().find(|f| f.0 == "test.txt").unwrap();
        assert_eq!(txt.1, b"test text document\r\n");
        assert!(txt.2);
        assert!(c.files.iter().all(|f| f.0 != "testlink"), "links are never extracted");

        let mut c = Collect::new();
        assert_eq!(extract(&fixture("test_read_format_rar5_stored.rar"), None, &mut c), 0);
        assert_eq!(c.files, vec![("helloworld.txt".to_string(), b"hello libarchive test suite!\n".to_vec(), true)]);
    }

    #[test]
    fn passwords_missing_wrong_and_right() {
        let enc = fixture("test_read_format_rar5_encrypted.rar");
        let mut c = Collect::new();
        assert_eq!(extract(&enc, None, &mut c), sys::ERAR_MISSING_PASSWORD);
        let mut c = Collect::new();
        assert_eq!(extract(&enc, Some("nope"), &mut c), sys::ERAR_BAD_PASSWORD);
        let mut c = Collect::new();
        c.only = Some("b.txt".into());
        assert_eq!(extract(&enc, Some("password"), &mut c), 0);
        assert_eq!(c.files[0].1, b"This is from b.txt");
        let hp = fixture("test_read_format_rar5_encrypted_filenames.rar");
        assert!(list(&hp, None).is_err());
        let (flags, entries) = list(&hp, Some("password")).unwrap();
        assert!(flags & sys::ROADF_ENCHEADERS != 0);
        assert_eq!(entries.len(), 4);
    }

    #[test]
    fn volumes_are_followed_from_the_first_part() {
        let mut c = Collect::new();
        assert_eq!(extract(&fixture("test_rar_multivolume_single_file.part1.rar"), None, &mut c), 0);
        assert_eq!(c.files.len(), 1);
        assert_eq!(c.files[0].1.len(), 20111);
        assert_eq!(crc32fast::hash(&c.files[0].1), 0x5e05a663);
    }

    #[test]
    fn the_sink_can_cancel() {
        let mut c = Collect::new();
        c.stop_after = Some(1);
        assert_eq!(extract(&fixture("test_read_format_rar5_multiple_files.rar"), None, &mut c), CANCELLED);
    }

    #[test]
    fn not_a_rar_is_reported() {
        let p = std::env::temp_dir().join("strata-not-a-rar.rar");
        std::fs::write(&p, b"definitely not RAR").unwrap();
        assert!(list(&p.to_string_lossy(), None).is_err());
        assert_eq!(dos_time_ms(0), 0);
        let dos = ((2020 - 1980) << 25) | (1 << 21) | (2 << 16) | (3 << 11) | (4 << 5) | 3;
        assert_eq!(dos_time_ms(dos), 1_577_934_246_000);
    }
}
