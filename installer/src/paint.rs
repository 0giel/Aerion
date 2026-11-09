//! Drawing into the window's pixels: smooth rounded shapes, the glow and the logo. Pixels are
//! 0xAARRGGBB, top row first.

pub struct Canvas<'a> {
    pub px: &'a mut [u32],
    pub w: i32,
    pub h: i32,
}

fn mix(a: u32, b: u32, t: f32) -> u32 {
    let ch = |s: u32| {
        let (x, y) = (((a >> s) & 255) as f32, ((b >> s) & 255) as f32);
        ((x + (y - x) * t).round() as u32) << s
    };
    0xFF00_0000 | ch(16) | ch(8) | ch(0)
}

impl Canvas<'_> {
    fn blend(&mut self, x: i32, y: i32, color: u32, alpha: f32) {
        if x >= 0 && y >= 0 && x < self.w && y < self.h && alpha > 0.0 {
            let i = (y * self.w + x) as usize;
            self.px[i] = mix(self.px[i], color, alpha.min(1.0));
        }
    }

    pub fn clear(&mut self, color: u32) {
        self.px.fill(0xFF00_0000 | color);
    }

    /// Light spreading from a point and fading out with the distance.
    pub fn glow(&mut self, cx: f32, cy: f32, radius: f32, color: u32, strength: f32) {
        for y in 0..self.h {
            for x in 0..self.w {
                let d = ((x as f32 - cx).powi(2) + (y as f32 - cy).powi(2)).sqrt() / radius;
                if d < 1.0 {
                    let t = 1.0 - d;
                    self.blend(x, y, color, t * t * strength);
                }
            }
        }
    }

    /// A rounded rectangle with soft edges.
    #[allow(clippy::too_many_arguments)]
    pub fn rounded(&mut self, x: f32, y: f32, w: f32, h: f32, r: f32, color: u32, alpha: f32) {
        let r = r.min(w / 2.0).min(h / 2.0);
        let (cx, cy) = (x + w / 2.0, y + h / 2.0);
        for py in (y.floor() as i32)..=((y + h).ceil() as i32) {
            for px in (x.floor() as i32)..=((x + w).ceil() as i32) {
                let qx = (px as f32 + 0.5 - cx).abs() - (w / 2.0 - r);
                let qy = (py as f32 + 0.5 - cy).abs() - (h / 2.0 - r);
                let outside =
                    (qx.max(0.0).powi(2) + qy.max(0.0).powi(2)).sqrt() + qx.max(qy).min(0.0) - r;
                let cover = (0.5 - outside).clamp(0.0, 1.0);
                self.blend(px, py, color, cover * alpha);
            }
        }
    }

    /// A square RGBA picture scaled to `size` pixels, averaging what each pixel covers.
    pub fn image(&mut self, rgba: &[u8], side: i32, x: i32, y: i32, size: i32) {
        let step = side as f32 / size as f32;
        for dy in 0..size {
            for dx in 0..size {
                let (x0, y0) = ((dx as f32 * step) as i32, (dy as f32 * step) as i32);
                let (x1, y1) = (
                    (((dx + 1) as f32 * step).ceil() as i32).min(side),
                    (((dy + 1) as f32 * step).ceil() as i32).min(side),
                );
                let (mut r, mut g, mut b, mut a, mut n) = (0f32, 0f32, 0f32, 0f32, 0f32);
                for sy in y0..y1.max(y0 + 1) {
                    for sx in x0..x1.max(x0 + 1) {
                        let p = &rgba[(sy * side + sx) as usize * 4..][..4];
                        let al = p[3] as f32 / 255.0;
                        r += p[0] as f32 * al;
                        g += p[1] as f32 * al;
                        b += p[2] as f32 * al;
                        a += al;
                        n += 1.0;
                    }
                }
                if a > 0.0 {
                    let c = ((r / a) as u32) << 16 | ((g / a) as u32) << 8 | (b / a) as u32;
                    self.blend(x + dx, y + dy, c, a / n);
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rounded_shape_fills_the_middle_and_leaves_the_corner() {
        let mut px = vec![0xFF00_0000; 100];
        let mut c = Canvas {
            px: &mut px,
            w: 10,
            h: 10,
        };
        c.rounded(0.0, 0.0, 10.0, 10.0, 4.0, 0xFFFFFF, 1.0);
        assert_eq!(px[55], 0xFFFF_FFFF);
        assert_eq!(px[0], 0xFF00_0000);
    }

    #[test]
    fn image_keeps_transparency_and_scales() {
        // Left column opaque red, right column transparent.
        let rgba = [255, 0, 0, 255, 0, 0, 0, 0, 255, 0, 0, 255, 0, 0, 0, 0];
        let mut px = vec![0xFF00_0000; 4];
        let mut c = Canvas {
            px: &mut px,
            w: 2,
            h: 2,
        };
        c.image(&rgba, 2, 0, 0, 2);
        assert_eq!(px[0], 0xFFFF_0000);
        assert_eq!(px[1], 0xFF00_0000);
    }
}
