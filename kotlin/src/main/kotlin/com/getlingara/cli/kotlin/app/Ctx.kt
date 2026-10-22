// What one command sees: the app, its streams and the profile. The session
// verbs (`call`, `bearer`, `email`) are `auth`'s extensions on it, as the
// Rust kit's are `impl Ctx` blocks in `auth::session`.
package com.getlingara.cli.kotlin.app

import com.getlingara.cli.kotlin.output.Output

class Ctx(
    val app: App,
    val out: Output,
    var profile: String,
    /** `--profile` or `LINGARA_PROFILE` chose it, so `platform.toml`'s `[platform] profile` may not (16.10.26ae D4). */
    val profileChosen: Boolean,
)
