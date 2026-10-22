//! Statements and values over the tokens: `const <handle> =
//! lingara.<kind>(<value>)`, one after another. A second call argument is a
//! script, which refuses the file there and then.

use std::collections::BTreeSet;

use super::lex::{Tok, Token};
use super::{Lit, Member, Pos, Refusal, Statement};

const KINDS: [&str; 4] = ["client", "secret", "webhook", "app"];

struct Parser {
    toks: Vec<Token>,
    i: usize,
    handles: BTreeSet<String>,
    end: Pos,
}

impl Parser {
    fn peek(&self) -> Option<&Token> {
        self.toks.get(self.i)
    }

    fn next(&mut self) -> Result<Token, Refusal> {
        let t = self.peek().cloned().ok_or(Refusal::at("syntax", self.end))?;
        self.i += 1;
        Ok(t)
    }

    fn punct(&mut self, c: char) -> Result<Pos, Refusal> {
        let t = self.next()?;
        if t.tok == Tok::Punct(c) { Ok(t.at) } else { Err(Refusal::at("syntax", t.at)) }
    }

    fn ident(&mut self) -> Result<(String, Pos), Refusal> {
        match self.next()? {
            Token { tok: Tok::Ident(s), at } => Ok((s, at)),
            t => Err(Refusal::at("syntax", t.at)),
        }
    }

    fn word(&mut self, want: &str) -> Result<Pos, Refusal> {
        let (s, at) = self.ident()?;
        if s == want { Ok(at) } else { Err(Refusal::at("syntax", at)) }
    }

    fn statement(&mut self) -> Result<Statement, Refusal> {
        let at = self.word("const")?;
        let (handle, handle_at) = self.ident()?;
        if self.handles.contains(&handle) {
            return Err(Refusal::at("handle", handle_at));
        }
        self.punct('=')?;
        self.word("lingara")?;
        self.punct('.')?;
        let (kind, kind_at) = self.ident()?;
        if !KINDS.contains(&kind.as_str()) {
            return Err(Refusal::at("syntax", kind_at));
        }
        self.punct('(')?;
        let value = self.value()?;
        if self.peek().is_some_and(|t| t.tok == Tok::Punct(',')) {
            return Err(Refusal::Scripted(handle));
        }
        self.punct(')')?;
        // Declared only now: a value may name the statements above it, never its own.
        self.handles.insert(handle.clone());
        Ok(Statement { handle, kind, value, at })
    }

    fn value(&mut self) -> Result<Lit, Refusal> {
        let t = self.next()?;
        Ok(match t.tok {
            Tok::Str(s) => Lit::Str(s),
            Tok::Num(n) => Lit::Num(n),
            Tok::Ident(w) if w == "true" || w == "false" => Lit::Bool(w == "true"),
            Tok::Ident(w) if w == "null" => Lit::Null,
            Tok::Ident(w) if self.handles.contains(&w) => Lit::Ref(w),
            Tok::Ident(_) => return Err(Refusal::at("reference", t.at)),
            Tok::Punct('[') => Lit::Arr(self.items(']', |p| p.value())?),
            Tok::Punct('{') => Lit::Obj(self.object()?, t.at),
            Tok::Punct(_) => return Err(Refusal::at("syntax", t.at)),
        })
    }

    /// `item {, item} [,] close`, the opener already read.
    fn items<T>(&mut self, close: char, mut item: impl FnMut(&mut Parser) -> Result<T, Refusal>) -> Result<Vec<T>, Refusal> {
        let mut out = Vec::new();
        loop {
            if self.peek().is_some_and(|t| t.tok == Tok::Punct(close)) {
                self.i += 1;
                return Ok(out);
            }
            out.push(item(self)?);
            match self.next()? {
                Token { tok: Tok::Punct(c), .. } if c == close => return Ok(out),
                Token { tok: Tok::Punct(','), .. } => {}
                t => return Err(Refusal::at("syntax", t.at)),
            }
        }
    }

    fn object(&mut self) -> Result<Vec<Member>, Refusal> {
        let mut seen = BTreeSet::new();
        self.items('}', |p| {
            let (key, at) = match p.next()? {
                Token { tok: Tok::Ident(s) | Tok::Str(s), at } => (s, at),
                t => return Err(Refusal::at("syntax", t.at)),
            };
            if !seen.insert(key.clone()) {
                return Err(Refusal::at("key", at));
            }
            p.punct(':')?;
            Ok(Member { key, at, value: p.value()? })
        })
    }
}

/// Every statement of the body, which ends at `end`.
pub fn statements(toks: Vec<Token>, end: Pos) -> Result<Vec<Statement>, Refusal> {
    let mut p = Parser { toks, i: 0, handles: BTreeSet::new(), end };
    let mut out = Vec::new();
    while p.peek().is_some() {
        out.push(p.statement()?);
    }
    Ok(out)
}
