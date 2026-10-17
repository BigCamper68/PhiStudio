use ab_glyph::{Font, FontRef, GlyphId, PxScale, ScaleFont};
use glyph_brush_layout::{GlyphPositioner, Layout, SectionGeometry, SectionText};
use std::{fs, io::{self, Write}};

// Export the metrics using exactly the ab_glyph version in Phira's Cargo.lock.
fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<_> = std::env::args().collect();
    let bytes = fs::read(&args[1])?;
    let font = FontRef::try_from_slice(&bytes)?;
    let face = ttf_parser::Face::parse(&bytes, 0)?;
    for name in face.names() {
        if [0, 1, 2, 4, 6, 13, 14].contains(&name.name_id) {
            if let Some(s) = name.to_string() { eprintln!("name {}: {}", name.name_id, s); }
        }
    }
    let mut out = io::BufWriter::new(fs::File::create(&args[2])?);
    out.write_all(b"PHF2")?;
    let mut int = |value: i32| out.write_all(&value.to_be_bytes());
    int(font.units_per_em().unwrap() as i32)?;
    int(font.ascent_unscaled() as i32)?;
    int(font.descent_unscaled() as i32)?;
    int(font.line_gap_unscaled() as i32)?;
    let count = face.number_of_glyphs();
    int(count as i32)?;
    for id in 0..count { int(font.h_advance_unscaled(GlyphId(id)) as i32)?; }
    for id in 0..count { int(font.h_side_bearing_unscaled(GlyphId(id)) as i32)?; }
    // codepoint_ids() deduplicates GLYPH ids, so it loses Unicode aliases (CJK!).
    // Enumerate every cmap codepoint, then use the actual Font::glyph_id lookup.
    let mut points = std::collections::BTreeSet::new();
    if let Some(cmap) = face.tables().cmap {
        for subtable in cmap.subtables {
            if subtable.is_unicode() { subtable.codepoints(|cp| { points.insert(cp); }); }
        }
    }
    let map: Vec<_> = points.into_iter().filter_map(|cp| char::from_u32(cp).map(|c| (cp, font.glyph_id(c).0)))
        .filter(|(_, id)| *id != 0).collect();
    int(map.len() as i32)?;
    for (cp, id) in map { int(cp as i32)?; int(id as i32)?; }
    let mut kern = Vec::new();
    if face.tables().kern.is_some() {
        for left in 0..count {
            for right in 0..count {
                let value = font.kern_unscaled(GlyphId(left), GlyphId(right)) as i32;
                if value != 0 { kern.push((((left as u32) << 16) | right as u32, value)); }
            }
        }
    }
    int(kern.len() as i32)?;
    for (key, value) in kern { int(key as i32)?; int(value)?; }
    out.flush()?;
    println!("units={} ascent={} descent={} gap={}", font.units_per_em().unwrap(), font.ascent_unscaled(), font.descent_unscaled(), font.line_gap_unscaled());
    // Independent glyph_brush_layout oracle; baseline/advance, not ink bounds.
    for scale in [16.384, 32.768, 40.96, 76.8] {
        for text in ["0000000", "1000000", "12345", "AUTOPLAY", "COMBO", "AVATAR", "PhiStudio", "Тест чарта", "中文", "A   V", " A "] {
            let glyphs = Layout::default().calculate_glyphs(&[font.clone()], &SectionGeometry::default(),
                &[SectionText { text, scale: PxScale::from(scale), ..SectionText::default() }]);
            let scaled = font.as_scaled(scale);
            let width = glyphs.last().map_or(0., |g| g.glyph.position.x + scaled.h_advance(g.glyph.id));
            let positions = glyphs.iter().map(|g| g.glyph.position.x.to_string()).collect::<Vec<_>>().join(",");
            println!("{}\t{}\t{}\t{}\t{}", scale, text, width, scaled.ascent(), positions);
        }
    }
    for text in ["A very long song title", "中文Тестチャート title", "😀 title"] {
        let scale = 40.;
        let scaled = font.as_scaled(scale);
        let glyphs = Layout::default().calculate_glyphs(&[font.clone()], &SectionGeometry::default(),
            &[SectionText { text, scale: PxScale::from(scale), ..SectionText::default() }]);
        let end = |g: &glyph_brush_layout::SectionGlyph| g.glyph.position.x + scaled.h_advance(g.glyph.id);
        for max in [0., 40., 100., 1000.] {
            let full = glyphs.last().map_or(0., end);
            let (width, clipped) = if full <= max { (full, text.to_string()) } else {
                let ellipsis = scaled.h_advance(scaled.glyph_id('…'));
                if ellipsis > max { (0., String::new()) } else {
                    let index = glyphs.partition_point(|g| end(g) <= max - ellipsis);
                    let st = if index == 0 { 0. } else { end(&glyphs[index - 1]) };
                    let byte = if index == 0 { 0 } else { glyphs[index - 1].byte_index };
                    let byte = text[..byte].char_indices().next_back().map_or(0, |(i, _)| i);
                    (st + ellipsis, format!("{}…", &text[..byte]))
                }
            };
            println!("CLIP\t{}\t{}\t{}\t{}\t{}", scale, max, text, width, clipped);
        }
    }
    // The multiline branch of prpr DrawText uses glyph_bounds, NOT ink bounds,
    // and paragraph anchor(0.5,0.5), NOT centered alignment on each line.
    for scale in [25.6, 40.96, 76.8, 84.] {
        for text in ["", "j", "I", "jY", "AVATAR", "Текст 中文", "Short\nMuch longer line",
            "\nLeading", "\n\nA\n\nB", "A\n", "A\n\n", "A\\nB", "A \nB", "A\tB",
            "A\rB", "\r\nA\r\nB\r\n", "A\u{85}B", "A\u{2028}B", "A\u{2029}B", "A\u{b}B", "A\u{c}B",
            "  text  ", "\n", "\t", "😀 text"] {
            let glyphs = Layout::default().calculate_glyphs(&[font.clone()], &SectionGeometry::default(),
                &[SectionText { text, scale: PxScale::from(scale), ..SectionText::default() }]);
            let mut bounds = None::<ab_glyph::Rect>;
            for sg in &glyphs {
                let g = font.glyph_bounds(&sg.glyph);
                bounds = Some(bounds.map_or(g, |b| ab_glyph::Rect {
                    min: ab_glyph::point(b.min.x.min(g.min.x), b.min.y.min(g.min.y)),
                    max: ab_glyph::point(b.max.x.max(g.max.x), b.max.y.max(g.max.y)),
                }));
            }
            let mut bounds = bounds.unwrap_or_default();
            bounds.min.x = bounds.min.x.max(0.); bounds.min.y = bounds.min.y.max(0.);
            let scaled = font.as_scaled(scale);
            let height = bounds.height() + text.chars().take_while(|c| *c == '\n').count() as f32
                * scaled.line_gap() * 3. + scaled.descent();
            let left = -bounds.min.x - bounds.width() / 2.;
            let top = -bounds.min.y - height / 2.;
            let ids = glyphs.iter().map(|g| g.glyph.id.0.to_string()).collect::<Vec<_>>().join(",");
            let positions = glyphs.iter().flat_map(|g| [g.glyph.position.x + left, g.glyph.position.y + top])
                .map(|p| p.to_string()).collect::<Vec<_>>().join(",");
            let hex = text.as_bytes().iter().map(|byte| format!("{:02x}", byte)).collect::<String>();
            println!("STORY\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}", scale, hex,
                bounds.min.x, bounds.min.y, bounds.width(), height, ids, positions);
        }
    }
    Ok(())
}
