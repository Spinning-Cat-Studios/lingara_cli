//! The terminal the ritual talks to (D5 step 1): interactive means stdin
//! **and** stderr are terminals, and prompts go to stderr, so
//! `lingara apps delete x > out.json` still prompts.

use std::io::{BufRead, IsTerminal};

pub trait Terminal {
    fn interactive(&self) -> bool;
    /// One line from stdin, `None` at end of input.
    fn read_line(&mut self) -> Option<String>;
}

pub struct Stdio;

impl Terminal for Stdio {
    fn interactive(&self) -> bool {
        std::io::stdin().is_terminal() && std::io::stderr().is_terminal()
    }

    fn read_line(&mut self) -> Option<String> {
        let mut line = String::new();
        match std::io::stdin().lock().read_line(&mut line) {
            Ok(0) | Err(_) => None,
            Ok(_) => Some(line),
        }
    }
}
