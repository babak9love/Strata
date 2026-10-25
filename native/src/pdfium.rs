use jni::objects::{JByteBuffer, JObject, JObjectArray, JString};
use jni::sys::{jfloatArray, jint, jlong, jobjectArray};
use jni::JNIEnv;
use std::collections::{HashMap, HashSet};
use std::ffi::{c_char, c_double, c_float, c_int, c_uint, c_ulong, c_void, CString};
use std::fs::File;
use std::os::unix::fs::FileExt;
use std::os::unix::io::FromRawFd;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;

type Handle = *mut c_void;

#[repr(C)]
struct LibraryConfig {
    version: c_int,
    user_font_paths: *const *const c_char,
    isolate: *mut c_void,
    v8_slot: c_uint,
    platform: *mut c_void,
}

#[repr(C)]
struct FileAccess {
    len: c_ulong,
    get_block: extern "C" fn(*mut c_void, c_ulong, *mut u8, c_ulong) -> c_int,
    param: *mut c_void,
}

#[repr(C)]
#[derive(Default, Clone, Copy)]
struct RectF {
    left: c_float,
    top: c_float,
    right: c_float,
    bottom: c_float,
}

#[repr(C)]
#[derive(Default, Clone, Copy)]
struct Matrix {
    a: c_float,
    b: c_float,
    c: c_float,
    d: c_float,
    e: c_float,
    f: c_float,
}

#[repr(C)]
#[derive(Default, Clone, Copy)]
struct SizeF {
    width: c_float,
    height: c_float,
}

#[repr(C)]
struct Pause {
    version: c_int,
    need_to_pause_now: extern "C" fn(*mut Pause) -> c_int,
    user: *mut c_void,
}

#[repr(C)]
struct FormInfo {
    version: c_int,
    release: Option<extern "C" fn(*mut FormInfo)>,
    invalidate: Option<extern "C" fn(*mut FormInfo, Handle, c_double, c_double, c_double, c_double)>,
    output_selected_rect: Option<extern "C" fn(*mut FormInfo, Handle, c_double, c_double, c_double, c_double)>,
    set_cursor: Option<extern "C" fn(*mut FormInfo, c_int)>,
    set_timer: Option<extern "C" fn(*mut FormInfo, c_int, *mut c_void) -> c_int>,
    kill_timer: Option<extern "C" fn(*mut FormInfo, c_int)>,
    get_local_time: Option<extern "C" fn()>,
    on_change: Option<extern "C" fn(*mut FormInfo)>,
    get_page: Option<extern "C" fn(*mut FormInfo, Handle, c_int) -> Handle>,
    get_current_page: Option<extern "C" fn(*mut FormInfo, Handle) -> Handle>,
    get_rotation: Option<extern "C" fn(*mut FormInfo, Handle) -> c_int>,
    execute_named_action: Option<extern "C" fn(*mut FormInfo, *const c_char)>,
    set_text_field_focus: Option<extern "C" fn(*mut FormInfo, *const u16, c_ulong, c_int)>,
    do_uri_action: Option<extern "C" fn(*mut FormInfo, *const c_char)>,
    do_goto_action: Option<extern "C" fn(*mut FormInfo, c_int, c_int, *mut c_float, c_int)>,
    js_platform: *mut c_void,
    xfa_disabled: c_int,
    rest: [usize; 32],
}

extern "C" fn form_invalidate(_: *mut FormInfo, _: Handle, _: c_double, _: c_double, _: c_double, _: c_double) {}
extern "C" fn form_set_cursor(_: *mut FormInfo, _: c_int) {}
extern "C" fn form_set_timer(_: *mut FormInfo, _: c_int, _: *mut c_void) -> c_int {
    0
}
extern "C" fn form_kill_timer(_: *mut FormInfo, _: c_int) {}
extern "C" fn form_on_change(_: *mut FormInfo) {}
extern "C" fn form_get_page(_: *mut FormInfo, _: Handle, _: c_int) -> Handle {
    std::ptr::null_mut()
}
extern "C" fn form_get_current_page(_: *mut FormInfo, _: Handle) -> Handle {
    std::ptr::null_mut()
}
extern "C" fn form_get_rotation(_: *mut FormInfo, _: Handle) -> c_int {
    0
}
extern "C" fn form_named_action(_: *mut FormInfo, _: *const c_char) {}

impl FormInfo {
    fn new() -> FormInfo {
        FormInfo {
            version: 1,
            release: None,
            invalidate: Some(form_invalidate),
            output_selected_rect: None,
            set_cursor: Some(form_set_cursor),
            set_timer: Some(form_set_timer),
            kill_timer: Some(form_kill_timer),
            get_local_time: None,
            on_change: Some(form_on_change),
            get_page: Some(form_get_page),
            get_current_page: Some(form_get_current_page),
            get_rotation: Some(form_get_rotation),
            execute_named_action: Some(form_named_action),
            set_text_field_focus: None,
            do_uri_action: None,
            do_goto_action: None,
            js_platform: std::ptr::null_mut(),
            xfa_disabled: 1,
            rest: [0; 32],
        }
    }
}

macro_rules! api {
    ($($name:ident : fn($($arg:ty),*) $(-> $ret:ty)?;)*) => {
        #[allow(non_snake_case)]
        struct Api { $($name: unsafe extern "C" fn($($arg),*) $(-> $ret)?,)* }
        impl Api {
            unsafe fn load(lib: *mut c_void) -> Option<Api> {
                Some(Api {
                    $($name: {
                        let p = libc::dlsym(lib, concat!(stringify!($name), "\0").as_ptr() as *const c_char);
                        if p.is_null() {
                            return None;
                        }
                        std::mem::transmute::<*mut c_void, unsafe extern "C" fn($($arg),*) $(-> $ret)?>(p)
                    },)*
                })
            }
        }
    };
}

api! {
    FPDF_InitLibraryWithConfig: fn(*const LibraryConfig);
    FPDF_LoadCustomDocument: fn(*mut FileAccess, *const c_char) -> Handle;
    FPDF_CloseDocument: fn(Handle);
    FPDF_GetLastError: fn() -> c_ulong;
    FPDF_GetPageCount: fn(Handle) -> c_int;
    FPDF_GetPageSizeByIndexF: fn(Handle, c_int, *mut SizeF) -> c_int;
    FPDF_LoadPage: fn(Handle, c_int) -> Handle;
    FPDF_ClosePage: fn(Handle);
    FPDF_GetPageWidthF: fn(Handle) -> c_float;
    FPDF_GetPageHeightF: fn(Handle) -> c_float;
    FPDF_PageToDevice: fn(Handle, c_int, c_int, c_int, c_int, c_int, c_double, c_double, *mut c_int, *mut c_int) -> c_int;
    FPDFBitmap_CreateEx: fn(c_int, c_int, c_int, *mut c_void, c_int) -> Handle;
    FPDFBitmap_FillRect: fn(Handle, c_int, c_int, c_int, c_int, c_ulong) -> c_int;
    FPDFBitmap_Destroy: fn(Handle);
    FPDF_RenderPageBitmap_Start: fn(Handle, Handle, c_int, c_int, c_int, c_int, c_int, c_int, *mut Pause) -> c_int;
    FPDF_RenderPage_Continue: fn(Handle, *mut Pause) -> c_int;
    FPDF_RenderPage_Close: fn(Handle);
    FPDFDOC_InitFormFillEnvironment: fn(Handle, *mut FormInfo) -> Handle;
    FPDFDOC_ExitFormFillEnvironment: fn(Handle);
    FORM_OnAfterLoadPage: fn(Handle, Handle);
    FORM_OnBeforeClosePage: fn(Handle, Handle);
    FPDF_FFLDraw: fn(Handle, Handle, Handle, c_int, c_int, c_int, c_int, c_int, c_int);
    FPDF_SetFormFieldHighlightAlpha: fn(Handle, u8);
    FPDFText_LoadPage: fn(Handle) -> Handle;
    FPDFText_ClosePage: fn(Handle);
    FPDFText_CountChars: fn(Handle) -> c_int;
    FPDFText_GetUnicode: fn(Handle, c_int) -> c_uint;
    FPDFText_GetLooseCharBox: fn(Handle, c_int, *mut RectF) -> c_int;
    FPDFText_GetCharOrigin: fn(Handle, c_int, *mut c_double, *mut c_double) -> c_int;
    FPDFText_GetFontSize: fn(Handle, c_int) -> c_double;
    FPDFText_GetMatrix: fn(Handle, c_int, *mut Matrix) -> c_int;
    FPDFText_IsGenerated: fn(Handle, c_int) -> c_int;
    FPDFText_IsHyphen: fn(Handle, c_int) -> c_int;
    FPDFText_HasUnicodeMapError: fn(Handle, c_int) -> c_int;
    FPDFLink_Enumerate: fn(Handle, *mut c_int, *mut Handle) -> c_int;
    FPDFLink_GetAnnotRect: fn(Handle, *mut RectF) -> c_int;
    FPDFLink_GetDest: fn(Handle, Handle) -> Handle;
    FPDFLink_GetAction: fn(Handle) -> Handle;
    FPDFAction_GetType: fn(Handle) -> c_ulong;
    FPDFAction_GetDest: fn(Handle, Handle) -> Handle;
    FPDFAction_GetURIPath: fn(Handle, Handle, *mut c_void, c_ulong) -> c_ulong;
    FPDFDest_GetDestPageIndex: fn(Handle, Handle) -> c_int;
    FPDFDest_GetLocationInPage: fn(Handle, *mut c_int, *mut c_int, *mut c_int, *mut c_float, *mut c_float, *mut c_float) -> c_int;
    FPDFLink_LoadWebLinks: fn(Handle) -> Handle;
    FPDFLink_CountWebLinks: fn(Handle) -> c_int;
    FPDFLink_GetURL: fn(Handle, c_int, *mut u16, c_int) -> c_int;
    FPDFLink_CountRects: fn(Handle, c_int) -> c_int;
    FPDFLink_GetRect: fn(Handle, c_int, c_int, *mut c_double, *mut c_double, *mut c_double, *mut c_double) -> c_int;
    FPDFLink_CloseWebLinks: fn(Handle);
    FPDFBookmark_GetFirstChild: fn(Handle, Handle) -> Handle;
    FPDFBookmark_GetNextSibling: fn(Handle, Handle) -> Handle;
    FPDFBookmark_GetTitle: fn(Handle, *mut c_void, c_ulong) -> c_ulong;
    FPDFBookmark_GetDest: fn(Handle, Handle) -> Handle;
    FPDFBookmark_GetAction: fn(Handle) -> Handle;
    FPDFBookmark_GetCount: fn(Handle) -> c_int;
    FPDF_GetMetaText: fn(Handle, *const c_char, *mut c_void, c_ulong) -> c_ulong;
    FPDF_GetPageLabel: fn(Handle, c_int, *mut c_void, c_ulong) -> c_ulong;
    FPDF_GetFileVersion: fn(Handle, *mut c_int) -> c_int;
    FPDF_GetSecurityHandlerRevision: fn(Handle) -> c_int;
    FPDF_GetDocPermissions: fn(Handle) -> c_ulong;
    FPDFCatalog_IsTagged: fn(Handle) -> c_int;
}

struct Doc {
    handle: Handle,
    form: Handle,
    _form_info: Box<FormInfo>,
    _access: Box<FileAccess>,
    _file: Box<File>,
    pages: Vec<(c_int, Handle)>,
}

struct State {
    api: Api,
    docs: HashMap<i64, Doc>,
    next: i64,
}

unsafe impl Send for State {}

static STATE: Mutex<Option<State>> = Mutex::new(None);
static CANCEL: AtomicBool = AtomicBool::new(false);

const RENDER_ANNOTATIONS: c_int = 0x01;
const REVERSE_BYTE_ORDER: c_int = 0x10;
const FORMAT_BGRA: c_int = 4;
const RENDER_TO_BE_CONTINUED: c_int = 1;
const RENDER_FAILED: c_int = 3;
const ACTION_GOTO: c_ulong = 1;
const ACTION_URI: c_ulong = 3;
const TEXT_STRIDE: usize = 12;
const LINK_STRIDE: usize = 9;
const OUTLINE_STRIDE: usize = 5;
const MAX_OUTLINE: usize = 20000;
const PAGE_CACHE: usize = 4;

extern "C" fn read_block(param: *mut c_void, position: c_ulong, buf: *mut u8, size: c_ulong) -> c_int {
    if param.is_null() || buf.is_null() {
        return 0;
    }
    let file = unsafe { &*(param as *const File) };
    let out = unsafe { std::slice::from_raw_parts_mut(buf, size as usize) };
    let mut done = 0usize;
    while done < out.len() {
        match file.read_at(&mut out[done..], position as u64 + done as u64) {
            Ok(0) => return 0,
            Ok(n) => done += n,
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(_) => return 0,
        }
    }
    1
}

extern "C" fn pause_now(_: *mut Pause) -> c_int {
    CANCEL.load(Ordering::Relaxed) as c_int
}

fn lock() -> std::sync::MutexGuard<'static, Option<State>> {
    match STATE.lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    }
}

fn init(path: Option<String>) -> i32 {
    let mut guard = lock();
    if guard.is_some() {
        return 0;
    }
    let name = path.unwrap_or_else(|| "libpdfium.so".to_string());
    let Ok(c) = CString::new(name) else {
        return -1;
    };
    let lib = unsafe { libc::dlopen(c.as_ptr(), libc::RTLD_NOW | libc::RTLD_LOCAL) };
    if lib.is_null() {
        return -1;
    }
    let Some(api) = (unsafe { Api::load(lib) }) else {
        return -2;
    };
    let config = LibraryConfig {
        version: 2,
        user_font_paths: std::ptr::null(),
        isolate: std::ptr::null_mut(),
        v8_slot: 0,
        platform: std::ptr::null_mut(),
    };
    unsafe { (api.FPDF_InitLibraryWithConfig)(&config) };
    *guard = Some(State { api, docs: HashMap::new(), next: 1 });
    0
}

fn open_file(file: File, password: Option<String>) -> i64 {
    let len = match file.metadata() {
        Ok(m) => m.len(),
        Err(_) => return -2,
    };
    let file = Box::new(file);
    let mut access = Box::new(FileAccess {
        len: len as c_ulong,
        get_block: read_block,
        param: &*file as *const File as *mut c_void,
    });
    let password = match password {
        Some(p) => match CString::new(p) {
            Ok(c) => Some(c),
            Err(_) => return -4,
        },
        None => None,
    };
    let mut guard = lock();
    let Some(state) = guard.as_mut() else {
        return -100;
    };
    let api = &state.api;
    let pw = password.as_ref().map(|c| c.as_ptr()).unwrap_or(std::ptr::null());
    let handle = unsafe { (api.FPDF_LoadCustomDocument)(&mut *access, pw) };
    if handle.is_null() {
        let code = unsafe { (api.FPDF_GetLastError)() } as i64;
        return if code > 0 { -code } else { -1 };
    }
    let mut info = Box::new(FormInfo::new());
    let form = unsafe { (api.FPDFDOC_InitFormFillEnvironment)(handle, &mut *info) };
    if !form.is_null() {
        unsafe { (api.FPDF_SetFormFieldHighlightAlpha)(form, 0) };
    }
    let id = state.next;
    state.next += 1;
    state.docs.insert(id, Doc { handle, form, _form_info: info, _access: access, _file: file, pages: Vec::new() });
    id
}

fn close_page(api: &Api, form: Handle, page: Handle) {
    unsafe {
        if !form.is_null() {
            (api.FORM_OnBeforeClosePage)(page, form);
        }
        (api.FPDF_ClosePage)(page);
    }
}

fn page(api: &Api, doc: &mut Doc, index: c_int) -> Handle {
    if let Some(pos) = doc.pages.iter().position(|(i, _)| *i == index) {
        let entry = doc.pages.remove(pos);
        doc.pages.push(entry);
        return entry.1;
    }
    let count = unsafe { (api.FPDF_GetPageCount)(doc.handle) };
    if index < 0 || index >= count {
        return std::ptr::null_mut();
    }
    let p = unsafe { (api.FPDF_LoadPage)(doc.handle, index) };
    if p.is_null() {
        return p;
    }
    if !doc.form.is_null() {
        unsafe { (api.FORM_OnAfterLoadPage)(p, doc.form) };
    }
    doc.pages.push((index, p));
    while doc.pages.len() > PAGE_CACHE {
        let (_, old) = doc.pages.remove(0);
        close_page(api, doc.form, old);
    }
    p
}

fn close_doc(api: &Api, mut doc: Doc) {
    for (_, p) in doc.pages.drain(..) {
        close_page(api, doc.form, p);
    }
    unsafe {
        if !doc.form.is_null() {
            (api.FPDFDOC_ExitFormFillEnvironment)(doc.form);
        }
        (api.FPDF_CloseDocument)(doc.handle);
    }
}

#[derive(Clone, Copy)]
struct Affine {
    a: f64,
    b: f64,
    c: f64,
    d: f64,
    e: f64,
    f: f64,
}

impl Affine {
    fn point(&self, x: f64, y: f64) -> (f64, f64) {
        (self.a * x + self.c * y + self.e, self.b * x + self.d * y + self.f)
    }

    fn vector(&self, x: f64, y: f64) -> (f64, f64) {
        (self.a * x + self.c * y, self.b * x + self.d * y)
    }

    fn rect(&self, l: f64, t: f64, r: f64, b: f64) -> [f32; 4] {
        let p = [self.point(l, t), self.point(r, t), self.point(l, b), self.point(r, b)];
        let mut out = [f64::MAX, f64::MAX, f64::MIN, f64::MIN];
        for (x, y) in p {
            out[0] = out[0].min(x);
            out[1] = out[1].min(y);
            out[2] = out[2].max(x);
            out[3] = out[3].max(y);
        }
        [out[0] as f32, out[1] as f32, out[2] as f32, out[3] as f32]
    }
}

fn display(api: &Api, page: Handle) -> (Affine, f64, f64) {
    let w = unsafe { (api.FPDF_GetPageWidthF)(page) } as f64;
    let h = unsafe { (api.FPDF_GetPageHeightF)(page) } as f64;
    let scale = 64.0;
    let sx = (w * scale).round().clamp(1.0, 2.0e9) as c_int;
    let sy = (h * scale).round().clamp(1.0, 2.0e9) as c_int;
    let kx = if sx > 0 { w / sx as f64 } else { 0.0 };
    let ky = if sy > 0 { h / sy as f64 } else { 0.0 };
    let map = |x: f64, y: f64| -> (f64, f64) {
        let mut dx: c_int = 0;
        let mut dy: c_int = 0;
        unsafe { (api.FPDF_PageToDevice)(page, 0, 0, sx, sy, 0, x, y, &mut dx, &mut dy) };
        (dx as f64 * kx, dy as f64 * ky)
    };
    let o = map(0.0, 0.0);
    let px = map(1000.0, 0.0);
    let py = map(0.0, 1000.0);
    let t = Affine {
        a: (px.0 - o.0) / 1000.0,
        b: (px.1 - o.1) / 1000.0,
        c: (py.0 - o.0) / 1000.0,
        d: (py.1 - o.1) / 1000.0,
        e: o.0,
        f: o.1,
    };
    (t, w, h)
}

fn utf16_string(buf: &[u16]) -> String {
    let end = buf.iter().position(|&c| c == 0).unwrap_or(buf.len());
    String::from_utf16_lossy(&buf[..end])
}

fn bookmark_title(api: &Api, bm: Handle) -> String {
    let n = unsafe { (api.FPDFBookmark_GetTitle)(bm, std::ptr::null_mut(), 0) } as usize;
    if n < 2 || n > 1 << 20 {
        return String::new();
    }
    let mut buf = vec![0u16; n / 2 + 1];
    unsafe { (api.FPDFBookmark_GetTitle)(bm, buf.as_mut_ptr() as *mut c_void, (buf.len() * 2) as c_ulong) };
    utf16_string(&buf)
}

fn meta_text(api: &Api, doc: Handle, tag: &str) -> String {
    let Ok(c) = CString::new(tag) else {
        return String::new();
    };
    let n = unsafe { (api.FPDF_GetMetaText)(doc, c.as_ptr(), std::ptr::null_mut(), 0) } as usize;
    if n < 2 || n > 1 << 20 {
        return String::new();
    }
    let mut buf = vec![0u16; n / 2 + 1];
    unsafe { (api.FPDF_GetMetaText)(doc, c.as_ptr(), buf.as_mut_ptr() as *mut c_void, (buf.len() * 2) as c_ulong) };
    utf16_string(&buf)
}

fn page_label(api: &Api, doc: Handle, index: c_int) -> String {
    let n = unsafe { (api.FPDF_GetPageLabel)(doc, index, std::ptr::null_mut(), 0) } as usize;
    if n < 2 || n > 1 << 16 {
        return String::new();
    }
    let mut buf = vec![0u16; n / 2 + 1];
    unsafe { (api.FPDF_GetPageLabel)(doc, index, buf.as_mut_ptr() as *mut c_void, (buf.len() * 2) as c_ulong) };
    utf16_string(&buf)
}

fn action_uri(api: &Api, doc: Handle, action: Handle) -> String {
    let n = unsafe { (api.FPDFAction_GetURIPath)(doc, action, std::ptr::null_mut(), 0) } as usize;
    if n == 0 || n > 1 << 16 {
        return String::new();
    }
    let mut buf = vec![0u8; n];
    unsafe { (api.FPDFAction_GetURIPath)(doc, action, buf.as_mut_ptr() as *mut c_void, n as c_ulong) };
    let end = buf.iter().position(|&c| c == 0).unwrap_or(buf.len());
    String::from_utf8_lossy(&buf[..end]).into_owned()
}

fn dest_target(api: &Api, doc: Handle, dest: Handle) -> (f32, f32, f32, f32) {
    if dest.is_null() {
        return (-1.0, 0.0, 0.0, 0.0);
    }
    let page = unsafe { (api.FPDFDest_GetDestPageIndex)(doc, dest) };
    let (mut has_x, mut has_y, mut has_zoom) = (0, 0, 0);
    let (mut x, mut y, mut zoom) = (0.0f32, 0.0f32, 0.0f32);
    let ok = unsafe { (api.FPDFDest_GetLocationInPage)(dest, &mut has_x, &mut has_y, &mut has_zoom, &mut x, &mut y, &mut zoom) };
    if ok == 0 || has_y == 0 {
        return (page as f32, 0.0, 0.0, 0.0);
    }
    (page as f32, 1.0, if has_x != 0 { x } else { 0.0 }, y)
}

fn float_array(env: &mut JNIEnv, data: &[f32]) -> jfloatArray {
    let Ok(len) = i32::try_from(data.len()) else {
        return std::ptr::null_mut();
    };
    match env.new_float_array(len) {
        Ok(arr) => {
            if env.set_float_array_region(&arr, 0, data).is_err() {
                return std::ptr::null_mut();
            }
            arr.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

fn string_array<'a>(env: &mut JNIEnv<'a>, items: &[String]) -> Option<JObjectArray<'a>> {
    let len = i32::try_from(items.len()).ok()?;
    let arr = env.new_object_array(len, "java/lang/String", JObject::null()).ok()?;
    for (i, s) in items.iter().enumerate() {
        let js = env.new_string(s).ok()?;
        env.set_object_array_element(&arr, i as i32, &js).ok()?;
        env.delete_local_ref(js).ok()?;
    }
    Some(arr)
}

fn pair(env: &mut JNIEnv, floats: &[f32], strings: &[String]) -> jobjectArray {
    let numbers = float_array(env, floats);
    if numbers.is_null() {
        return std::ptr::null_mut();
    }
    let numbers = unsafe { JObject::from_raw(numbers) };
    let Some(texts) = string_array(env, strings) else {
        return std::ptr::null_mut();
    };
    let Ok(out) = env.new_object_array(2, "java/lang/Object", JObject::null()) else {
        return std::ptr::null_mut();
    };
    if env.set_object_array_element(&out, 0, &numbers).is_err() || env.set_object_array_element(&out, 1, &texts).is_err() {
        return std::ptr::null_mut();
    }
    out.into_raw()
}

fn optional_string(env: &mut JNIEnv, s: &JString) -> Option<String> {
    if s.is_null() {
        return None;
    }
    env.get_string(s).ok().map(String::from)
}

#[no_mangle]
pub extern "system" fn Java_dev_cluvex_strata_core_pdf_view_Pdfium_nInit<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    path: JString<'local>,
) -> jint {
    let p = optional_string(&mut env, &path);
    init(p)
}

#[no_mangle]
pub extern "system" fn Java_dev_cluvex_strata_core_pdf_view_Pdfium_nOpenPath<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    path: JString<'local>,
    password: JString<'local>,
) -> jlong {
    let Some(p) = optional_string(&mut env, &path) else {
        return -2;
    };
    let pw = optional_string(&mut env, &password);
    match File::open(&p) {
        Ok(f) => open_file(f, pw),
        Err(_) => -2,
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_cluvex_strata_core_pdf_view_Pdfium_nOpenFd<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    fd: jint,
    password: JString<'local>,
) -> jlong {
    let pw = optional_string(&mut env, &password);
    let copy = unsafe { libc::dup(fd) };
    if copy < 0 {
        return -2;
    }
    let file = unsafe { File::from_raw_fd(copy) };
    open_file(file, pw)
}

#[no_mangle]
pub extern "system" fn Java_dev_cluvex_strata_core_pdf_view_Pdfium_nClose<'local>(
    _env: JNIEnv<'local>,
    _this: JObject<'local>,
    id: jlong,
) {
    let mut guard = lock();
    let Some(state) = guard.as_mut() else {
        return;
    };
    if let Some(doc) = state.docs.remove(&id) {
        close_doc(&state.api, doc);
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_cluvex_strata_core_pdf_view_Pdfium_nPageSizes<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    id: jlong,
) -> jfloatArray {
    let sizes = {
        let mut guard = lock();
        let Some(state) = guard.as_mut() else {
            return std::ptr::null_mut();
        };
        let api = &state.api;
        let Some(doc) = state.docs.get(&id) else {
            return std::ptr::null_mut();
        };
        let n = unsafe { (api.FPDF_GetPageCount)(doc.handle) }.max(0);
        let mut out = Vec::with_capacity(n as usize * 2);
        for i in 0..n {
            let mut s = SizeF::default();
            if unsafe { (api.FPDF_GetPageSizeByIndexF)(doc.handle, i, &mut s) } == 0 {
                s = SizeF { width: 612.0, height: 792.0 };
            }
            out.push(s.width);
            out.push(s.height);
        }
        out
    };
    float_array(&mut env, &sizes)
}

#[no_mangle]
pub extern "system" fn Java_dev_cluvex_strata_core_pdf_view_Pdfium_nCancel<'local>(_env: JNIEnv<'local>, _this: JObject<'local>) {
    CANCEL.store(true, Ordering::Relaxed);
}

#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_dev_cluvex_strata_core_pdf_view_Pdfium_nRender<'local>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
    id: jlong,
    index: jint,
    buffer: JByteBuffer<'local>,
    width: jint,
    height: jint,
    start_x: jint,
    start_y: jint,
    size_x: jint,
    size_y: jint,
    flags: jint,
) -> jint {
    if width <= 0 || height <= 0 || size_x <= 0 || size_y <= 0 {
        return -1;
    }
    let Ok(addr) = env.get_direct_buffer_address(&buffer) else {
        return -1;
    };
    let Ok(capacity) = env.get_direct_buffer_capacity(&buffer) else {
        return -1;
    };
    let needed = width as usize * height as usize * 4;
    if addr.is_null() || capacity < needed {
        return -1;
    }
    let mut guard = lock();
    let Some(state) = guard.as_mut() else {
        return -100;
    };
    let api = &state.api;
    let Some(doc) = state.docs.get_mut(&id) else {
        return -2;
    };
    let p = page(api, doc, index);
    if p.is_null() {
        return -3;
    }
    let form = doc.form;
    let bitmap = unsafe { (api.FPDFBitmap_CreateEx)(width, height, FORMAT_BGRA, addr as *mut c_void, width * 4) };
    if bitmap.is_null() {
        return -1;
    }
    unsafe { (api.FPDFBitmap_FillRect)(bitmap, 0, 0, width, height, 0xFFFF_FFFF) };
    CANCEL.store(false, Ordering::Relaxed);
    let mut pause = Pause { version: 1, need_to_pause_now: pause_now, user: std::ptr::null_mut() };
    let render_flags = flags | REVERSE_BYTE_ORDER;
    let mut status = unsafe { (api.FPDF_RenderPageBitmap_Start)(bitmap, p, start_x, start_y, size_x, size_y, 0, render_flags, &mut pause) };
    while status == RENDER_TO_BE_CONTINUED && !CANCEL.load(Ordering::Relaxed) {
        status = unsafe { (api.FPDF_RenderPage_Continue)(p, &mut pause) };
    }
    let cancelled = status == RENDER_TO_BE_CONTINUED;
    unsafe { (api.FPDF_RenderPage_Close)(p) };
    if !cancelled && !form.is_null() && flags & RENDER_ANNOTATIONS != 0 {
        unsafe { (api.FPDF_FFLDraw)(form, bitmap, p, start_x, start_y, size_x, size_y, 0, render_flags) };
    }
    unsafe { (api.FPDFBitmap_Destroy)(bitmap) };
    if cancelled {
        1
    } else if status == RENDER_FAILED {
        -4
    } else {
        0
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_cluvex_strata_core_pdf_view_Pdfium_nPageTransform<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    id: jlong,
    index: jint,
) -> jfloatArray {
    let data = {
        let mut guard = lock();
        let Some(state) = guard.as_mut() else {
            return std::ptr::null_mut();
        };
        let api = &state.api;
        let Some(doc) = state.docs.get_mut(&id) else {
            return std::ptr::null_mut();
        };
        let p = page(api, doc, index);
        if p.is_null() {
            return std::ptr::null_mut();
        }
        let (t, w, h) = display(api, p);
        [t.a as f32, t.b as f32, t.c as f32, t.d as f32, t.e as f32, t.f as f32, w as f32, h as f32]
    };
    float_array(&mut env, &data)
}

#[no_mangle]
pub extern "system" fn Java_dev_cluvex_strata_core_pdf_view_Pdfium_nPageText<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    id: jlong,
    index: jint,
) -> jfloatArray {
    let data = {
        let mut guard = lock();
        let Some(state) = guard.as_mut() else {
            return std::ptr::null_mut();
        };
        let api = &state.api;
        let Some(doc) = state.docs.get_mut(&id) else {
            return std::ptr::null_mut();
        };
        let p = page(api, doc, index);
        if p.is_null() {
            return std::ptr::null_mut();
        }
        let (t, _, _) = display(api, p);
        let tp = unsafe { (api.FPDFText_LoadPage)(p) };
        if tp.is_null() {
            Vec::new()
        } else {
            let n = unsafe { (api.FPDFText_CountChars)(tp) }.max(0);
            let mut out = Vec::with_capacity(n as usize * TEXT_STRIDE);
            for i in 0..n {
                let cp = unsafe { (api.FPDFText_GetUnicode)(tp, i) };
                let mut flags = 0u32;
                if unsafe { (api.FPDFText_IsGenerated)(tp, i) } == 1 {
                    flags |= 1;
                }
                if unsafe { (api.FPDFText_IsHyphen)(tp, i) } == 1 {
                    flags |= 2;
                }
                if unsafe { (api.FPDFText_HasUnicodeMapError)(tp, i) } == 1 {
                    flags |= 4;
                }
                let mut r = RectF::default();
                unsafe { (api.FPDFText_GetLooseCharBox)(tp, i, &mut r) };
                let bx = t.rect(r.left as f64, r.top as f64, r.right as f64, r.bottom as f64);
                let (mut ox, mut oy) = (0.0f64, 0.0f64);
                unsafe { (api.FPDFText_GetCharOrigin)(tp, i, &mut ox, &mut oy) };
                let o = t.point(ox, oy);
                let size = unsafe { (api.FPDFText_GetFontSize)(tp, i) };
                let mut m = Matrix { a: 1.0, b: 0.0, c: 0.0, d: 1.0, e: 0.0, f: 0.0 };
                if unsafe { (api.FPDFText_GetMatrix)(tp, i, &mut m) } == 0 {
                    m = Matrix { a: 1.0, b: 0.0, c: 0.0, d: 1.0, e: 0.0, f: 0.0 };
                }
                let effective = size * (m.c as f64).hypot(m.d as f64);
                let (dx, dy) = t.vector(m.a as f64, m.b as f64);
                let len = dx.hypot(dy);
                let (dx, dy) = if len > 1e-9 { (dx / len, dy / len) } else { (1.0, 0.0) };
                out.extend_from_slice(&[cp as f32, flags as f32, bx[0], bx[1], bx[2], bx[3], o.0 as f32, o.1 as f32, effective as f32, dx as f32, dy as f32, 0.0]);
            }
            unsafe { (api.FPDFText_ClosePage)(tp) };
            out
        }
    };
    float_array(&mut env, &data)
}

#[no_mangle]
pub extern "system" fn Java_dev_cluvex_strata_core_pdf_view_Pdfium_nLinks<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    id: jlong,
    index: jint,
) -> jobjectArray {
    let (floats, strings) = {
        let mut guard = lock();
        let Some(state) = guard.as_mut() else {
            return std::ptr::null_mut();
        };
        let api = &state.api;
        let Some(doc) = state.docs.get_mut(&id) else {
            return std::ptr::null_mut();
        };
        let handle = doc.handle;
        let p = page(api, doc, index);
        if p.is_null() {
            return std::ptr::null_mut();
        }
        let (t, _, _) = display(api, p);
        let mut floats: Vec<f32> = Vec::new();
        let mut strings: Vec<String> = Vec::new();
        let mut pos: c_int = 0;
        let mut link: Handle = std::ptr::null_mut();
        let mut guard_count = 0;
        while unsafe { (api.FPDFLink_Enumerate)(p, &mut pos, &mut link) } != 0 && guard_count < 10000 {
            guard_count += 1;
            let mut r = RectF::default();
            if unsafe { (api.FPDFLink_GetAnnotRect)(link, &mut r) } == 0 {
                continue;
            }
            let bx = t.rect(r.left as f64, r.top as f64, r.right as f64, r.bottom as f64);
            let mut dest = unsafe { (api.FPDFLink_GetDest)(handle, link) };
            let mut uri = String::new();
            if dest.is_null() {
                let action = unsafe { (api.FPDFLink_GetAction)(link) };
                if !action.is_null() {
                    match unsafe { (api.FPDFAction_GetType)(action) } {
                        ACTION_GOTO => dest = unsafe { (api.FPDFAction_GetDest)(handle, action) },
                        ACTION_URI => uri = action_uri(api, handle, action),
                        _ => {}
                    }
                }
            }
            if !dest.is_null() {
                let (target, has_y, x, y) = dest_target(api, handle, dest);
                if target < 0.0 {
                    continue;
                }
                floats.extend_from_slice(&[bx[0], bx[1], bx[2], bx[3], 0.0, target, has_y, x, y]);
                strings.push(String::new());
            } else if !uri.is_empty() {
                floats.extend_from_slice(&[bx[0], bx[1], bx[2], bx[3], 1.0, -1.0, 0.0, 0.0, 0.0]);
                strings.push(uri);
            }
        }
        let tp = unsafe { (api.FPDFText_LoadPage)(p) };
        if !tp.is_null() {
            let web = unsafe { (api.FPDFLink_LoadWebLinks)(tp) };
            if !web.is_null() {
                let count = unsafe { (api.FPDFLink_CountWebLinks)(web) }.clamp(0, 10000);
                for k in 0..count {
                    let n = unsafe { (api.FPDFLink_GetURL)(web, k, std::ptr::null_mut(), 0) };
                    if n <= 1 || n > 1 << 16 {
                        continue;
                    }
                    let mut buf = vec![0u16; n as usize];
                    unsafe { (api.FPDFLink_GetURL)(web, k, buf.as_mut_ptr(), n) };
                    let url = utf16_string(&buf);
                    let rects = unsafe { (api.FPDFLink_CountRects)(web, k) }.clamp(0, 1000);
                    for ri in 0..rects {
                        let (mut l, mut tp_, mut r, mut b) = (0.0f64, 0.0f64, 0.0f64, 0.0f64);
                        if unsafe { (api.FPDFLink_GetRect)(web, k, ri, &mut l, &mut tp_, &mut r, &mut b) } == 0 {
                            continue;
                        }
                        let bx = t.rect(l, tp_, r, b);
                        floats.extend_from_slice(&[bx[0], bx[1], bx[2], bx[3], 2.0, -1.0, 0.0, 0.0, 0.0]);
                        strings.push(url.clone());
                    }
                }
                unsafe { (api.FPDFLink_CloseWebLinks)(web) };
            }
            unsafe { (api.FPDFText_ClosePage)(tp) };
        }
        (floats, strings)
    };
    debug_assert_eq!(floats.len(), strings.len() * LINK_STRIDE);
    pair(&mut env, &floats, &strings)
}

#[allow(clippy::too_many_arguments)]
fn walk_outline(api: &Api, doc: Handle, parent: Handle, depth: usize, floats: &mut Vec<f32>, titles: &mut Vec<String>, seen: &mut HashSet<usize>) {
    if depth > 64 {
        return;
    }
    let mut bm = unsafe { (api.FPDFBookmark_GetFirstChild)(doc, parent) };
    while !bm.is_null() && titles.len() < MAX_OUTLINE {
        if !seen.insert(bm as usize) {
            break;
        }
        let title = bookmark_title(api, bm);
        let mut dest = unsafe { (api.FPDFBookmark_GetDest)(doc, bm) };
        if dest.is_null() {
            let action = unsafe { (api.FPDFBookmark_GetAction)(bm) };
            if !action.is_null() && unsafe { (api.FPDFAction_GetType)(action) } == ACTION_GOTO {
                dest = unsafe { (api.FPDFAction_GetDest)(doc, action) };
            }
        }
        let (target, has_y, _, y) = dest_target(api, doc, dest);
        let open = unsafe { (api.FPDFBookmark_GetCount)(bm) } > 0;
        floats.extend_from_slice(&[depth as f32, target, has_y, y, if open { 1.0 } else { 0.0 }]);
        titles.push(title);
        walk_outline(api, doc, bm, depth + 1, floats, titles, seen);
        bm = unsafe { (api.FPDFBookmark_GetNextSibling)(doc, bm) };
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_cluvex_strata_core_pdf_view_Pdfium_nOutline<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    id: jlong,
) -> jobjectArray {
    let (floats, titles) = {
        let guard = lock();
        let Some(state) = guard.as_ref() else {
            return std::ptr::null_mut();
        };
        let Some(doc) = state.docs.get(&id) else {
            return std::ptr::null_mut();
        };
        let mut floats = Vec::new();
        let mut titles = Vec::new();
        let mut seen = HashSet::new();
        walk_outline(&state.api, doc.handle, std::ptr::null_mut(), 0, &mut floats, &mut titles, &mut seen);
        (floats, titles)
    };
    debug_assert_eq!(floats.len(), titles.len() * OUTLINE_STRIDE);
    pair(&mut env, &floats, &titles)
}

#[no_mangle]
pub extern "system" fn Java_dev_cluvex_strata_core_pdf_view_Pdfium_nPageLabels<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    id: jlong,
) -> jobjectArray {
    let labels = {
        let guard = lock();
        let Some(state) = guard.as_ref() else {
            return std::ptr::null_mut();
        };
        let Some(doc) = state.docs.get(&id) else {
            return std::ptr::null_mut();
        };
        let n = unsafe { (state.api.FPDF_GetPageCount)(doc.handle) }.max(0);
        (0..n).map(|i| page_label(&state.api, doc.handle, i)).collect::<Vec<_>>()
    };
    string_array(&mut env, &labels).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_dev_cluvex_strata_core_pdf_view_Pdfium_nMeta<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    id: jlong,
) -> jobjectArray {
    let items = {
        let guard = lock();
        let Some(state) = guard.as_ref() else {
            return std::ptr::null_mut();
        };
        let Some(doc) = state.docs.get(&id) else {
            return std::ptr::null_mut();
        };
        let api = &state.api;
        let mut items: Vec<String> = ["Title", "Author", "Subject", "Keywords", "Creator", "Producer", "CreationDate", "ModDate"]
            .iter()
            .map(|t| meta_text(api, doc.handle, t))
            .collect();
        let mut version: c_int = 0;
        let has_version = unsafe { (api.FPDF_GetFileVersion)(doc.handle, &mut version) } != 0;
        items.push(if has_version { format!("{}.{}", version / 10, version % 10) } else { String::new() });
        items.push(unsafe { (api.FPDF_GetDocPermissions)(doc.handle) }.to_string());
        items.push(unsafe { (api.FPDF_GetSecurityHandlerRevision)(doc.handle) }.to_string());
        items.push(if unsafe { (api.FPDFCatalog_IsTagged)(doc.handle) } != 0 { "1".to_string() } else { "0".to_string() });
        items
    };
    string_array(&mut env, &items).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut())
}
