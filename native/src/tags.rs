//! Reading and writing audio tags (ID3v2, Vorbis comments, MP4 atoms, ...) through `lofty`.
//!
//! The file is never edited in place: the tags are written to a hidden copy next to it, and the copy then
//! replaces the original. A failed write therefore leaves the original untouched, and a player that still has the
//! old file open keeps reading the old data until it reloads.

use lofty::config::WriteOptions;
use lofty::file::{AudioFile, TaggedFile, TaggedFileExt};
use lofty::picture::{Picture, PictureType};
use lofty::probe::Probe;
use lofty::tag::{ItemKey, Tag, TagType};
use std::fs;
use std::io::Cursor;
use std::path::{Path, PathBuf};

#[allow(dead_code)]
pub const COVER_KEEP: i32 = 0;
pub const COVER_REMOVE: i32 = 1;
pub const COVER_REPLACE: i32 = 2;

/// Order of the strings exchanged with Kotlin (`TagEditor`).
/// 0 title, 1 artist, 2 album, 3 album artist, 4 year, 5 track "n/total", 6 disc "n/total", 7 genre, 8 comment, 9 format (read only).
pub const FIELD_COUNT: usize = 10;

fn open(path: &str) -> Result<TaggedFile, String> {
    Probe::open(path)
        .map_err(|e| e.to_string())?
        .guess_file_type()
        .map_err(|e| e.to_string())?
        .read()
        .map_err(|e| e.to_string())
}

fn text(tag: &Tag, key: &ItemKey) -> String {
    tag.get_string(key).unwrap_or("").to_string()
}

fn pair(tag: &Tag, number: &ItemKey, total: &ItemKey) -> String {
    let n = text(tag, number);
    if n.contains('/') {
        return n; // some files keep "3/12" in one value
    }
    let t = text(tag, total);
    match (n.is_empty(), t.is_empty()) {
        (true, true) => String::new(),
        (false, true) => n,
        (true, false) => format!("/{t}"),
        (false, false) => format!("{n}/{t}"),
    }
}

fn year(tag: &Tag) -> String {
    let y = text(tag, &ItemKey::Year);
    let y = if y.is_empty() { text(tag, &ItemKey::RecordingDate) } else { y };
    y.chars().take(4).collect()
}

fn read_tag(tagged: &TaggedFile) -> Option<&Tag> {
    tagged.primary_tag().or_else(|| tagged.first_tag())
}

pub fn read(path: &str) -> Result<Vec<String>, String> {
    let tagged = open(path)?;
    let mut out = vec![String::new(); FIELD_COUNT];
    if let Some(t) = read_tag(&tagged) {
        out[0] = text(t, &ItemKey::TrackTitle);
        out[1] = text(t, &ItemKey::TrackArtist);
        out[2] = text(t, &ItemKey::AlbumTitle);
        out[3] = text(t, &ItemKey::AlbumArtist);
        out[4] = year(t);
        out[5] = pair(t, &ItemKey::TrackNumber, &ItemKey::TrackTotal);
        out[6] = pair(t, &ItemKey::DiscNumber, &ItemKey::DiscTotal);
        out[7] = text(t, &ItemKey::Genre);
        out[8] = text(t, &ItemKey::Comment);
    }
    out[9] = format!("{:?}", tagged.file_type());
    Ok(out)
}

pub fn read_cover(path: &str) -> Result<Option<Vec<u8>>, String> {
    let tagged = open(path)?;
    let Some(t) = read_tag(&tagged) else { return Ok(None) };
    let pic = t
        .pictures()
        .iter()
        .find(|p| p.pic_type() == PictureType::CoverFront)
        .or_else(|| t.pictures().first());
    Ok(pic.map(|p| p.data().to_vec()))
}

fn set_text(tag: &mut Tag, key: ItemKey, value: &str) {
    tag.remove_key(&key);
    let v = value.trim();
    if !v.is_empty() {
        tag.insert_text(key, v.to_string());
    }
}

/// For ID3v2 the year is written as the recording date (TDRC); `add_tyer` adds the old TYER frame next to it afterwards.
/// The other formats have a plain year/date field.
fn set_year(tag: &mut Tag, value: &str) {
    tag.remove_key(&ItemKey::Year);
    tag.remove_key(&ItemKey::RecordingDate);
    let v = value.trim();
    if v.is_empty() {
        return;
    }
    if tag.tag_type() == TagType::Id3v2 {
        tag.insert_text(ItemKey::RecordingDate, v.to_string());
    } else {
        tag.insert_text(ItemKey::Year, v.to_string());
    }
}

/// "3/12", "3", "/12" or "" -> (3, 12) style parts; anything that is not a number is dropped.
fn parse_pair(s: &str) -> (Option<u32>, Option<u32>) {
    let mut it = s.splitn(2, '/');
    let n = it.next().and_then(|x| x.trim().parse::<u32>().ok());
    let t = it.next().and_then(|x| x.trim().parse::<u32>().ok());
    (n, t)
}

fn set_pair(tag: &mut Tag, number: ItemKey, total: ItemKey, value: &str) {
    let (n, t) = parse_pair(value);
    tag.remove_key(&number);
    tag.remove_key(&total);
    if let Some(n) = n {
        tag.insert_text(number, n.to_string());
    }
    if let Some(t) = t {
        tag.insert_text(total, t.to_string());
    }
}

fn apply(path: &str, f: &[String], cover_mode: i32, cover: Option<&[u8]>) -> Result<(), String> {
    let mut tagged = open(path)?;
    if tagged.primary_tag().is_none() {
        let tag_type = tagged.primary_tag_type();
        tagged.insert_tag(Tag::new(tag_type));
    }
    let tag = tagged.primary_tag_mut().ok_or("the format has no tag")?;

    set_text(tag, ItemKey::TrackTitle, &f[0]);
    set_text(tag, ItemKey::TrackArtist, &f[1]);
    set_text(tag, ItemKey::AlbumTitle, &f[2]);
    set_text(tag, ItemKey::AlbumArtist, &f[3]);
    set_year(tag, &f[4]);
    set_pair(tag, ItemKey::TrackNumber, ItemKey::TrackTotal, &f[5]);
    set_pair(tag, ItemKey::DiscNumber, ItemKey::DiscTotal, &f[6]);
    set_text(tag, ItemKey::Genre, &f[7]);
    set_text(tag, ItemKey::Comment, &f[8]);

    match cover_mode {
        COVER_REMOVE => {
            for _ in 0..tag.picture_count() {
                tag.remove_picture(0);
            }
        }
        COVER_REPLACE => {
            let bytes = cover.ok_or("no cover data")?;
            let mut pic = Picture::from_reader(&mut Cursor::new(bytes)).map_err(|e| e.to_string())?;
            pic.set_pic_type(PictureType::CoverFront);
            for _ in 0..tag.picture_count() {
                tag.remove_picture(0);
            }
            tag.push_picture(pic);
        }
        _ => {}
    }

    tagged
        .save_to_path(path, WriteOptions::default())
        .map_err(|e| e.to_string())
}

fn synchsafe(b: &[u8]) -> usize {
    ((b[0] as usize & 0x7f) << 21) | ((b[1] as usize & 0x7f) << 14) | ((b[2] as usize & 0x7f) << 7) | (b[3] as usize & 0x7f)
}

fn to_synchsafe(n: usize) -> [u8; 4] {
    [((n >> 21) & 0x7f) as u8, ((n >> 14) & 0x7f) as u8, ((n >> 7) & 0x7f) as u8, (n & 0x7f) as u8]
}

/// Android's parser and Samsung Music read the year only from the old TYER frame, while newer players read TDRC.
/// lofty writes just TDRC into an ID3v2.4 tag, so this puts a TYER frame next to it (best effort: on any doubt the file
/// is left as lofty wrote it). It uses the padding lofty leaves after the frames, or grows the tag a little.
fn add_tyer(path: &str, year: &str) {
    let digits: String = year.chars().filter(|c| c.is_ascii_digit()).take(4).collect();
    if digits.len() != 4 {
        return;
    }
    let _ = (|| -> std::io::Result<()> {
        use std::io::{Read, Seek, SeekFrom, Write};
        const MAX_REWRITE: u64 = 64 * 1024 * 1024;
        let mut f = fs::OpenOptions::new().read(true).write(true).open(path)?;
        let mut head = [0u8; 10];
        f.read_exact(&mut head)?;
        // Only ID3v2.4 tags without unsynchronisation, an extended header or a footer.
        if &head[0..3] != b"ID3" || head[3] != 4 || head[5] & 0xD0 != 0 {
            return Ok(());
        }
        let size = synchsafe(&head[6..10]);
        let mut body = vec![0u8; size];
        f.read_exact(&mut body)?;
        let mut pos = 0usize;
        while pos + 10 <= size && body[pos] != 0 {
            if &body[pos..pos + 4] == b"TYER" {
                return Ok(());
            }
            let len = synchsafe(&body[pos + 4..pos + 8]);
            if pos + 10 + len > size {
                return Ok(());
            }
            pos += 10 + len;
        }
        if body[pos..].iter().any(|&b| b != 0) {
            return Ok(());
        }
        let mut frame = Vec::with_capacity(15);
        frame.extend_from_slice(b"TYER");
        frame.extend_from_slice(&to_synchsafe(5));
        frame.extend_from_slice(&[0, 0]);
        frame.push(0); // ISO-8859-1
        frame.extend_from_slice(digits.as_bytes());
        if size - pos >= frame.len() {
            f.seek(SeekFrom::Start(10 + pos as u64))?;
            f.write_all(&frame)?;
            return Ok(());
        }
        // No room: grow the tag by one frame and move the audio after it.
        if f.metadata()?.len() > MAX_REWRITE {
            return Ok(());
        }
        let mut rest = Vec::new();
        f.read_to_end(&mut rest)?;
        let mut new_head = head;
        new_head[6..10].copy_from_slice(&to_synchsafe(size + frame.len()));
        f.seek(SeekFrom::Start(0))?;
        f.write_all(&new_head)?;
        f.write_all(&body[..pos])?;
        f.write_all(&frame)?;
        f.write_all(&body[pos..])?;
        f.write_all(&rest)?;
        Ok(())
    })();
}

fn temp_beside(src: &Path) -> PathBuf {
    let stem = src.file_stem().and_then(|s| s.to_str()).unwrap_or("audio");
    let ext = src.extension().and_then(|s| s.to_str()).unwrap_or("bin");
    src.with_file_name(format!(".{stem}.strata-tag.{ext}"))
}

pub fn write(path: &str, fields: &[String], cover_mode: i32, cover: Option<&[u8]>) -> Result<(), String> {
    if fields.len() < FIELD_COUNT - 1 {
        return Err("bad field list".into());
    }
    let src = Path::new(path);
    let tmp = temp_beside(src);
    let tmp_str = tmp.to_str().ok_or("bad path")?.to_string();
    fs::copy(src, &tmp).map_err(|e| e.to_string())?;
    if let Err(e) = apply(&tmp_str, fields, cover_mode, cover) {
        let _ = fs::remove_file(&tmp);
        return Err(e);
    }
    add_tyer(&tmp_str, &fields[4]);
    if fs::rename(&tmp, src).is_err() {
        // Rename can fail across mount quirks; fall back to copying the finished file over the original.
        let r = fs::copy(&tmp, src).map(|_| ()).map_err(|e| e.to_string());
        let _ = fs::remove_file(&tmp);
        return r;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::{parse_pair, synchsafe, to_synchsafe};

    #[test]
    fn synchsafe_roundtrip() {
        for n in [0usize, 1, 127, 128, 1024, 300_000] {
            assert_eq!(synchsafe(&to_synchsafe(n)), n);
        }
    }

    #[test]
    fn pairs() {
        assert_eq!(parse_pair("3/12"), (Some(3), Some(12)));
        assert_eq!(parse_pair(" 3 "), (Some(3), None));
        assert_eq!(parse_pair("/12"), (None, Some(12)));
        assert_eq!(parse_pair("abc"), (None, None));
    }
}
