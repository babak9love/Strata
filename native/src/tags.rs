//! Reading and writing audio tags (ID3v2, Vorbis comments, MP4 atoms, ...) through `lofty`.
//!
//! The file is never edited in place: the tags are written to a hidden copy next to it, and the copy then
//! replaces the original. A failed write therefore leaves the original untouched, and a player that still has the
//! old file open keeps reading the old data until it reloads.

use lofty::config::WriteOptions;
use lofty::file::{AudioFile, TaggedFile, TaggedFileExt};
use lofty::picture::{Picture, PictureType};
use lofty::probe::Probe;
use lofty::tag::{ItemKey, Tag};
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
    set_text(tag, ItemKey::Year, &f[4]);
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
    use super::parse_pair;

    #[test]
    fn pairs() {
        assert_eq!(parse_pair("3/12"), (Some(3), Some(12)));
        assert_eq!(parse_pair(" 3 "), (Some(3), None));
        assert_eq!(parse_pair("/12"), (None, Some(12)));
        assert_eq!(parse_pair("abc"), (None, None));
    }
}
