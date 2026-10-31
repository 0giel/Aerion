//! Read (not verify) JWT payloads, e.g. the `xuid` claim of a Minecraft access token.

use base64::Engine;

pub fn payload(token: &str) -> Option<serde_json::Value> {
    let part = token.split('.').nth(1)?;
    let bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(part.trim_end_matches('='))
        .ok()?;
    serde_json::from_slice(&bytes).ok()
}

pub fn claim_string(token: &str, claim: &str) -> Option<String> {
    let v = payload(token)?;
    match v.get(claim)? {
        serde_json::Value::String(s) => Some(s.clone()),
        serde_json::Value::Number(n) => Some(n.to_string()),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_claims() {
        let header = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(br#"{"alg":"none"}"#);
        let body = base64::engine::general_purpose::URL_SAFE_NO_PAD
            .encode(br#"{"xuid":"2535400000000000","exp":123}"#);
        let token = format!("{header}.{body}.sig");
        assert_eq!(
            claim_string(&token, "xuid").as_deref(),
            Some("2535400000000000")
        );
        assert_eq!(claim_string(&token, "exp").as_deref(), Some("123"));
        assert!(claim_string("garbage", "xuid").is_none());
    }
}
