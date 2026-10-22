//! The literal subset's tokens (spec/connection-literal.md): identifiers,
//! JSON-escaped strings in either quote, finite JSON numbers and
//! punctuation, each with its 1-based line and column. A comment, a template
//! literal or a character the subset does not know stops the scan.

use super::{Pos, Refusal};

#[derive(Debug, Clone, PartialEq)]
pub enum Tok {
    Ident(String),
    Str(String),
    Num(serde_json::Number),
    Punct(char),
}

#[derive(Debug, Clone)]
pub struct Token {
    pub tok: Tok,
    pub at: Pos,
}

struct Lexer {
    chars: Vec<char>,
    i: usize,
    line: usize,
    col: usize,
}

impl Lexer {
    fn pos(&self) -> Pos {
        Pos { line: self.line, column: self.col }
    }

    fn peek(&self, ahead: usize) -> Option<char> {
        self.chars.get(self.i + ahead).copied()
    }

    fn bump(&mut self) -> Option<char> {
        let c = self.chars.get(self.i).copied()?;
        self.i += 1;
        if c == '\n' {
            self.line += 1;
            self.col = 1;
        } else {
            self.col += 1;
        }
        Some(c)
    }

    fn take_while(&mut self, keep: impl Fn(char) -> bool) -> String {
        let mut out = String::new();
        while let Some(c) = self.peek(0).filter(|c| keep(*c)) {
            out.push(c);
            self.bump();
        }
        out
    }
}

fn ident_start(c: char) -> bool {
    c.is_ascii_alphabetic() || c == '_' || c == '$'
}

fn ident_char(c: char) -> bool {
    c.is_ascii_alphanumeric() || c == '_' || c == '$'
}

/// Every token from `text`, which starts at line `first_line`.
pub fn tokens(text: &str, first_line: usize) -> Result<Vec<Token>, Refusal> {
    let mut lx = Lexer { chars: text.chars().collect(), i: 0, line: first_line, col: 1 };
    let mut out = Vec::new();
    while let Some(c) = lx.peek(0) {
        let at = lx.pos();
        let tok = match c {
            ' ' | '\t' | '\r' | '\n' => {
                lx.bump();
                continue;
            }
            '/' if matches!(lx.peek(1), Some('/' | '*')) => return Err(Refusal::at("comment", at)),
            '`' => return Err(Refusal::at("text", at)),
            '"' | '\'' => Tok::Str(string(&mut lx, at)?),
            '-' | '0'..='9' => Tok::Num(number(&mut lx, at)?),
            c if ident_start(c) => Tok::Ident(lx.take_while(ident_char)),
            '{' | '}' | '[' | ']' | '(' | ')' | ',' | ':' | '=' | '.' => {
                lx.bump();
                Tok::Punct(c)
            }
            _ => return Err(Refusal::at("syntax", at)),
        };
        out.push(Token { tok, at });
    }
    Ok(out)
}

fn string(lx: &mut Lexer, at: Pos) -> Result<String, Refusal> {
    let quote = lx.bump().unwrap_or('"');
    let mut out = String::new();
    loop {
        match lx.bump() {
            None | Some('\n' | '\r' | '\t') => return Err(Refusal::at("text", at)),
            Some(c) if c == quote => return Ok(out),
            Some('\\') => out.push(escape(lx, quote).ok_or(Refusal::at("text", at))?),
            Some('$') if lx.peek(0) == Some('{') => return Err(Refusal::at("text", at)),
            Some(c) => out.push(c),
        }
    }
}

/// JSON's escapes, and `\'` inside single quotes.
fn escape(lx: &mut Lexer, quote: char) -> Option<char> {
    Some(match lx.bump()? {
        '"' => '"',
        '\\' => '\\',
        '/' => '/',
        'b' => '\u{8}',
        'f' => '\u{c}',
        'n' => '\n',
        'r' => '\r',
        't' => '\t',
        '\'' if quote == '\'' => '\'',
        'u' => {
            let hex: String = (0..4).filter_map(|_| lx.bump()).collect();
            char::from_u32(u32::from_str_radix(&hex, 16).ok()?)?
        }
        _ => return None,
    })
}

/// A finite JSON number: `-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?`.
fn number(lx: &mut Lexer, at: Pos) -> Result<serde_json::Number, Refusal> {
    let text = lx.take_while(|c| c.is_ascii_alphanumeric() || matches!(c, '-' | '+' | '.'));
    let digits = text.strip_prefix('-').unwrap_or(&text);
    let leading_zero = digits.len() > 1 && digits.starts_with('0') && digits.as_bytes()[1].is_ascii_digit();
    if leading_zero || !digits.starts_with(|c: char| c.is_ascii_digit()) {
        return Err(Refusal::at("number", at));
    }
    match serde_json::from_str::<serde_json::Number>(&text) {
        Ok(n) if n.as_f64().is_some_and(f64::is_finite) => Ok(n),
        _ => Err(Refusal::at("number", at)),
    }
}
