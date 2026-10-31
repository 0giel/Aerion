//! Parsing the game's stdout: log4j2 XML events (from Mojang's logging configs) and plain lines.

use serde::Serialize;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "lowercase")]
pub enum LogLevel {
    Trace,
    Debug,
    Info,
    Warn,
    Error,
    Fatal,
    /// Not from log4j (raw stdout/stderr, launcher messages).
    Raw,
}

impl LogLevel {
    fn parse(s: &str) -> Self {
        match s.trim().to_ascii_uppercase().as_str() {
            "TRACE" => LogLevel::Trace,
            "DEBUG" => LogLevel::Debug,
            "INFO" => LogLevel::Info,
            "WARN" | "WARNING" => LogLevel::Warn,
            "ERROR" | "SEVERE" => LogLevel::Error,
            "FATAL" => LogLevel::Fatal,
            _ => LogLevel::Raw,
        }
    }
}

#[derive(Debug, Clone, Serialize)]
#[cfg_attr(feature = "specta", derive(specta::Type))]
#[serde(rename_all = "camelCase")]
pub struct LogLine {
    /// Milliseconds since the UNIX epoch (game timestamp when available).
    pub time: f64,
    pub level: LogLevel,
    pub thread: Option<String>,
    pub logger: Option<String>,
    pub text: String,
}

const MAX_EVENT_BYTES: usize = 512 * 1024;

/// Incremental parser: feed it lines, it yields complete log lines.
#[derive(Default)]
pub struct LogParser {
    event: Option<String>,
}

fn now_ms() -> f64 {
    chrono::Utc::now().timestamp_millis() as f64
}

fn unescape(s: &str) -> String {
    s.replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}

fn attr(tag: &str, name: &str) -> Option<String> {
    let needle = format!("{name}=\"");
    let start = tag.find(&needle)? + needle.len();
    let end = tag[start..].find('"')? + start;
    Some(unescape(&tag[start..end]))
}

/// Text of an element, joining CDATA sections (log4j splits `]]>` across sections).
fn element_text(xml: &str, element: &str) -> Option<String> {
    let open = format!("<{element}>");
    let close = format!("</{element}>");
    let start = xml.find(&open)? + open.len();
    let end = xml[start..].find(&close)? + start;
    let inner = &xml[start..end];
    let mut out = String::new();
    let mut rest = inner;
    let mut found_cdata = false;
    while let Some(s) = rest.find("<![CDATA[") {
        found_cdata = true;
        let after = &rest[s + 9..];
        let e = after.find("]]>").unwrap_or(after.len());
        out.push_str(&after[..e]);
        rest = after.get(e + 3..).unwrap_or("");
    }
    Some(if found_cdata {
        out
    } else {
        unescape(inner.trim())
    })
}

/// Plain `[12:34:56] [Render thread/INFO]: message` lines (Forge early output, other layouts).
fn parse_pattern_line(line: &str) -> Option<(LogLevel, String, String)> {
    let rest = line.strip_prefix('[')?;
    let (_time, rest) = rest.split_once("] [")?;
    let (thread_level, msg) = rest.split_once("]: ")?;
    let (thread, level) = thread_level.rsplit_once('/')?;
    Some((LogLevel::parse(level), thread.to_owned(), msg.to_owned()))
}

impl LogParser {
    pub fn push(&mut self, line: &str) -> Option<LogLine> {
        if let Some(buf) = &mut self.event {
            buf.push_str(line);
            buf.push('\n');
            if line.contains("</log4j:Event>") || buf.len() > MAX_EVENT_BYTES {
                let xml = self.event.take().unwrap_or_default();
                return Some(Self::finish_event(&xml));
            }
            return None;
        }
        let trimmed = line.trim_start();
        if trimmed.starts_with("<log4j:Event") {
            if trimmed.contains("</log4j:Event>") {
                return Some(Self::finish_event(trimmed));
            }
            self.event = Some(format!("{trimmed}\n"));
            return None;
        }
        if let Some((level, thread, text)) = parse_pattern_line(line) {
            return Some(LogLine {
                time: now_ms(),
                level,
                thread: Some(thread),
                logger: None,
                text,
            });
        }
        Some(LogLine {
            time: now_ms(),
            level: LogLevel::Raw,
            thread: None,
            logger: None,
            text: line.to_owned(),
        })
    }

    /// Flush an unterminated event (process exited mid-event).
    pub fn finish(&mut self) -> Option<LogLine> {
        self.event.take().map(|xml| Self::finish_event(&xml))
    }

    fn finish_event(xml: &str) -> LogLine {
        let head_end = xml.find('>').unwrap_or(xml.len());
        let head = &xml[..head_end];
        let mut text = element_text(xml, "log4j:Message").unwrap_or_else(|| xml.to_owned());
        if let Some(t) = element_text(xml, "log4j:Throwable") {
            if !text.is_empty() {
                text.push('\n');
            }
            text.push_str(t.trim_end());
        }
        LogLine {
            time: attr(head, "timestamp")
                .and_then(|t| t.parse().ok())
                .unwrap_or_else(now_ms),
            level: attr(head, "level")
                .map(|l| LogLevel::parse(&l))
                .unwrap_or(LogLevel::Raw),
            thread: attr(head, "thread"),
            logger: attr(head, "logger"),
            text,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_xml_events_with_throwables() {
        let mut p = LogParser::default();
        let lines = [
            r#"<log4j:Event logger="net.minecraft.client.Minecraft" timestamp="1700000000123" level="INFO" thread="Render thread">"#,
            r#"  <log4j:Message><![CDATA[Setting user: Łukasz]]></log4j:Message>"#,
            r#"</log4j:Event>"#,
            r#"<log4j:Event logger="x" timestamp="1" level="ERROR" thread="main &amp; co">"#,
            r#"  <log4j:Message><![CDATA[Boom ]]]]><![CDATA[> multi"#,
            r#"line]]></log4j:Message>"#,
            r#"  <log4j:Throwable><![CDATA[java.lang.IllegalStateException: bad"#,
            "\tat a.b.C.d(C.java:1)",
            r#"]]></log4j:Throwable>"#,
            r#"</log4j:Event>"#,
        ];
        let out: Vec<LogLine> = lines.iter().filter_map(|l| p.push(l)).collect();
        assert_eq!(out.len(), 2);
        assert_eq!(out[0].text, "Setting user: Łukasz");
        assert_eq!(out[0].level, LogLevel::Info);
        assert_eq!(out[0].time, 1_700_000_000_123.0);
        assert_eq!(out[1].level, LogLevel::Error);
        assert_eq!(out[1].thread.as_deref(), Some("main & co"));
        assert!(
            out[1]
                .text
                .starts_with("Boom ]]> multi\nline\njava.lang.IllegalStateException: bad")
        );
        assert!(out[1].text.ends_with("(C.java:1)"));
    }

    #[test]
    fn plain_and_pattern_lines() {
        let mut p = LogParser::default();
        let l = p.push("[12:00:01] [main/WARN]: Something odd").unwrap();
        assert_eq!(
            (l.level, l.thread.as_deref(), l.text.as_str()),
            (LogLevel::Warn, Some("main"), "Something odd")
        );
        let raw = p.push("Exception in thread \"main\"").unwrap();
        assert_eq!(raw.level, LogLevel::Raw);
    }
}
