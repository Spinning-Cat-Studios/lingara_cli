// `login`, `logout` and `whoami` (ADR 16.10.26ad D3). Under CI credentials
// there is nothing to sign in or out of, so both exit `usage` with
// `login_ci_not_needed`.
package com.getlingara.cli.kotlin.commands

import com.getlingara.cli.kotlin.app.Ctx
import com.getlingara.cli.kotlin.auth.Cred
import com.getlingara.cli.kotlin.auth.SessionState
import com.getlingara.cli.kotlin.auth.Tokens
import com.getlingara.cli.kotlin.auth.browserLogin
import com.getlingara.cli.kotlin.auth.call
import com.getlingara.cli.kotlin.auth.ciPair
import com.getlingara.cli.kotlin.auth.deviceLogin
import com.getlingara.cli.kotlin.auth.saveRecord
import com.getlingara.cli.kotlin.core.Assets
import com.getlingara.cli.kotlin.core.Exit
import com.getlingara.cli.kotlin.core.Fail
import com.getlingara.cli.kotlin.core.str
import com.getlingara.cli.kotlin.http.Auth
import com.getlingara.cli.kotlin.http.Body
import com.getlingara.cli.kotlin.http.Fixed
import com.getlingara.cli.kotlin.http.Request
import com.getlingara.cli.kotlin.output.Table
import com.getlingara.cli.kotlin.store.ProfileRecord

object SessionCommands {
    private fun ciNotNeeded() = Fail(Exit.USAGE, "login_ci_not_needed", Assets.message("login_ci_not_needed"))

    fun login(ctx: Ctx, device: Boolean) {
        if (ctx.ciPair() != null) throw ciNotNeeded()
        val tokens = if (device) ctx.deviceLogin() else ctx.browserLogin()
        var record = Tokens.read(tokens).applyTo(ProfileRecord(), ctx.app.http.clock.now())
        // The new token, for this one read: whose is it?
        ctx.app.session = SessionState(Cred.Stored(record), record.accessToken, null, record.scopes)
        val me = ctx.call(Request(Fixed.DEVELOPER_ME.route))
        record = record.copy(developerId = me.str("developer_id"), email = me.str("email"))
        ctx.saveRecord(record)
        ctx.out.note(Assets.message("login_signed_in", "email" to record.email))
        ctx.out.nothing()
    }

    /** Revokes the refresh token (RFC 7009) and forgets the profile. A revoke that fails, the network being down say, still forgets it and warns. */
    fun logout(ctx: Ctx) {
        if (ctx.ciPair() != null) throw ciNotNeeded()
        val record = ctx.app.stores.load(ctx.profile) ?: throw Fail.auth("not signed in: nothing to sign out of")
        val form = listOf("token" to record.refreshToken, "token_type_hint" to "refresh_token", "client_id" to ctx.app.endpoints.clientId)
        val why = try {
            val reply = ctx.app.http.send(Request(Fixed.REVOKE.route, body = Body.Form(form)), Auth.Bearer(record.accessToken))
            if (reply.status in 200..299) null else "HTTP ${reply.status}"
        } catch (fail: Fail) {
            fail.text
        }
        if (why != null) ctx.out.note("warning: the refresh token could not be revoked ($why); forgetting it here anyway")
        ctx.app.stores.delete(ctx.profile)
        ctx.app.session = null
        ctx.out.nothing()
    }

    /** `EMAIL  DEVELOPER  SCOPES  PROFILE`, `SCOPES` from the token response. */
    fun whoami(ctx: Ctx) {
        val me = ctx.call(Request(Fixed.DEVELOPER_ME.route))
        if (ctx.out.json) return ctx.out.jsonValue(me)
        val scopes = ctx.app.session?.scopes.orEmpty()
        val row = listOf(Table.cell(me.path("email")), Table.cell(me.path("developer_id")), scopes, ctx.profile)
        ctx.out.print(Table.render(listOf("EMAIL", "DEVELOPER", "SCOPES", "PROFILE"), listOf(row)))
    }
}
