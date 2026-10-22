<?php

declare(strict_types=1);

namespace Lingara\Cli\Tests;

use Lingara\Cli\Canvas\Definition;
use Lingara\Cli\Canvas\Plan;
use Lingara\Cli\Canvas\Refusal;
use Lingara\Cli\Canvas\Steps;
use Lingara\Cli\Canvas\Stop;
use Lingara\Cli\Core\ExitCode;
use Lingara\Cli\Core\Fail;
use Lingara\Cli\Core\Json;
use Lingara\Cli\Core\TableRender;
use Lingara\Cli\Dev\DevHold;
use Lingara\Cli\Dev\RunState;
use Lingara\Cli\Dev\Signals;
use Lingara\Cli\Http\Answer;
use Lingara\Cli\Http\Classify;
use Lingara\Cli\Http\Route;
use Lingara\Cli\Http\WireResponse;
use Lingara\Cli\Store\FileStore;
use Lingara\Cli\Store\Stores;
use Lingara\Cli\Tests\Support\FakeTerminal;
use Lingara\Cli\Tests\Support\FakeTransport;
use Lingara\Cli\Tests\Support\Kit;
use Lingara\Cli\Version;
use PHPUnit\Framework\TestCase;

/**
 * The PHP kit's core, as ADR 16.10.26af AC20 lists it: AC19's (the confirm
 * ritual, the file-only store, the classifier's B2 rules, every
 * canvas-plans.json vector, the B1 table, `interrupted` returned as 130 on
 * SIGINT, an unknown option and a command prefix exiting 2), plus `-q`,
 * `-v`, `-n`, `list` and `app:li` all `usage`, and `apps dev` without
 * ext-pcntl exiting `tool_missing` with `tool_missing_php_ext`.
 */
final class CoreTest extends TestCase
{
    private const CLIENT = '0b9c4f0e-0000-4000-8000-000000000001';

    // 16.10.26af AC20
    public function testCore(): void
    {
        $this->confirmRitual();
        $this->fileOnlyStore();
        $this->classifierRules();
        $this->canvasVectors();
        $this->b1Table();
        $this->interruptedReturns130();
        $this->usageRefusals();
        $this->appsDevNeedsPcntl();
        $this->versionIsTheRepositorys();
    }

    /** @param list<string> $lines */
    private static function kit(bool $tty = false, array $lines = [], int $answers = 0): Kit
    {
        $client = FakeTransport::json(200, '{"id":"' . self::CLIENT . '","name":"My server","children":[{"kind":"app"}]}');
        $kit = new Kit(new FakeTransport(array_merge(array_fill(0, $answers, $client), [new WireResponse(204, [], '')])), new FakeTerminal($tty, $lines));
        $kit->signIn();
        return $kit;
    }

    private function confirmRitual(): void
    {
        // Non-interactive without both flags: refused before any request, the read included.
        $kit = self::kit();
        $this->assertSame(ExitCode::NotConfirmed, $kit->run(['client', 'delete', self::CLIENT, '-y'])->exit);
        $this->assertStringContainsString('(confirm_non_tty_refused)', $kit->stderr);
        $this->assertSame([], $kit->transport->sent);
        // Flagged: the canonical email travels in the DELETE's body, with no read for a prompt.
        $kit = self::kit();
        $this->assertSame(ExitCode::Ok, $kit->run(['client', 'delete', self::CLIENT, '-y', '--confirm', ' DEV@Conformance.invalid '])->exit);
        $this->assertCount(1, $kit->transport->sent);
        $this->assertSame('DELETE', $kit->transport->sent[0]['method']);
        $this->assertSame('{"confirm":"dev@conformance.invalid"}', $kit->transport->sent[0]['body']);
        $this->assertSame(ExitCode::NotConfirmed, self::kit()->run(['client', 'delete', self::CLIENT, '-y', '--confirm', 'x@y.invalid'])->exit);
        // Interactive: the read names the object, y then the typed email; a decline or a mismatch sends no DELETE.
        $kit = self::kit(true, ["y\n", "dev@conformance.invalid\n"], 1);
        $this->assertSame(ExitCode::Ok, $kit->run(['client', 'delete', self::CLIENT])->exit);
        $this->assertStringContainsString('Delete OAuth client My server', $kit->stderr);
        $this->assertStringContainsString('d…@conformance.invalid', $kit->stderr);
        $this->assertSame(['GET', 'DELETE'], array_column($kit->transport->sent, 'method'));
        foreach ([["no\n"], ["yes\n", "someone@else.invalid\n"]] as $typed) {
            $kit = self::kit(true, $typed, 1);
            $this->assertSame(ExitCode::NotConfirmed, $kit->run(['client', 'delete', self::CLIENT])->exit);
            $this->assertSame(['GET'], array_column($kit->transport->sent, 'method'));
        }
    }

    private function fileOnlyStore(): void
    {
        $record = FileStore::record(['email' => 'a@b.c', 'access_token' => 'lgr_at_1']);
        $dir = sys_get_temp_dir() . '/lingara-php-store-' . bin2hex(random_bytes(6));
        // Unset: the file is the default, and its first write per profile says so, once.
        $auto = new Stores(Stores::modeOf(null), new FileStore($dir));
        $this->assertSame("storing credentials in {$dir}/credentials.json; this kit has no keychain support", $auto->save('default', $record));
        $this->assertNull($auto->save('default', $record));
        $this->assertSame(0600, fileperms("{$dir}/credentials.json") & 0777);
        $this->assertSame(0700, fileperms($dir) & 0777);
        $auto->save('alpha', $record);
        $text = (string) file_get_contents("{$dir}/credentials.json");
        $this->assertStringStartsWith("{\n  \"schema\": 1,\n  \"profiles\": {\n    \"alpha\": {\n      \"developer_id\": \"\",", $text);
        // LINGARA_KEYRING=file is silent.
        $this->assertNull((new Stores(Stores::FILE, new FileStore($dir . '-f')))->save('default', $record));
        // LINGARA_KEYRING=keychain is auth_required with store_file_only's sentence, never a file write.
        $kit = new Kit(new FakeTransport(), new FakeTerminal(), ['LINGARA_KEYRING' => 'keychain']);
        $outcome = $kit->run(['whoami']);
        $this->assertSame(ExitCode::AuthRequired, $outcome->exit);
        $this->assertSame('store_file_only', $outcome->fail?->errorCode);
        $this->assertStringContainsString('this kit has no keychain support', $kit->stderr);
        $this->assertSame([], $kit->transport->sent);
        $this->assertFileDoesNotExist($kit->app->stores->file->path);
    }

    private function classifierRules(): void
    {
        $answer = static fn (string $route, int $status, string $body, bool $bearer = true): Answer => new Answer(
            new Route($route, 'GET', $route === 'oauth.token' ? '/oauth/token' : '/platform/x'),
            new WireResponse($status, [], $body),
            $bearer,
        );
        $cases = [
            [$answer('app.list', 404, '{"code":"platform_disabled","error":"x"}'), ExitCode::PlatformDisabled],
            [$answer('oauth.token', 400, '{"error":"invalid_grant","lingara_code":"platform_access_not_enabled"}', false), ExitCode::PlatformDisabled],
            [$answer('app.list', 403, '{"code":"platform_access_not_enabled","error":"x"}'), ExitCode::PlatformDisabled],
            [$answer('app.list', 401, ''), null],
            [$answer('oauth.token', 400, '{"error":"invalid_grant"}', false), ExitCode::AuthRequired],
            [$answer('oauth.token', 429, '{"error":"slow"}', false), ExitCode::Unavailable],
            [$answer('app.get', 404, '{"code":"object_not_found","error":"x"}'), ExitCode::NotFound],
            [$answer('developer.me', 404, '{"code":"developer_not_found","error":"x"}'), ExitCode::Refused],
            [$answer('app_dev.session_open', 409, '{"code":"dev_session_held","error":"x"}'), ExitCode::DevSessionHeld],
            [$answer('app_dev.session_renew', 409, '{"code":"dev_session_expired","error":"x"}'), ExitCode::DevSessionLost],
            [$answer('app_dev.patch', 409, '{"code":"version_conflict","error":"x"}'), ExitCode::Refused],
            [$answer('app.get', 404, ''), ExitCode::Failure],
            [$answer('app.create', 422, '{"code":"invalid","error":"x"}'), ExitCode::Refused],
            [$answer('app.list', 503, 'maintenance'), ExitCode::Unavailable],
            [$answer('app.get', 404, '<html>'), ExitCode::Failure],
            [$answer('app.get', 410, ''), ExitCode::Failure],
        ];
        foreach ($cases as $i => [$a, $want]) {
            $this->assertSame($want, Classify::classify($a), "rule case {$i}");
        }
        $this->assertSame('unexpected response from app.get', Classify::failFor($answer('app.get', 404, ''), ExitCode::Failure)->getMessage());
    }

    /** @return array<string, list<array{id: string, runtime_id: string, parent_id: string}>> */
    private static function live(mixed $v): array
    {
        $rows = static fn (string $k): array => array_map([Plan::class, 'row'], Json::items($v, $k));
        return ['client' => $rows('oauth_client'), 'webhook' => $rows('webhook_out'), 'app' => $rows('app')];
    }

    /** @param array<string, mixed> $step */
    private static function rendered(array $step): mixed
    {
        $argv = array_map(static fn (array $p): string => $p['text'] ?? (isset($p['made']) ? "<{$p['made']}>" : "<{$p['manifest']}.manifest>"), $step['argv']);
        return Json::decode(Json::encode(['handle' => $step['handle'], 'action' => $step['action'], 'records' => $step['records'], 'argv' => $argv]));
    }

    private function canvasVectors(): void
    {
        $file = dirname(__DIR__, 2) . '/conformance/vectors/canvas-plans.json';
        $vectors = Json::items(Json::decode((string) file_get_contents($file)), 'vectors');
        $this->assertGreaterThanOrEqual(8, count($vectors), 'every rule has its vector');
        foreach ($vectors as $v) {
            $this->canvasVector($v, Json::str($v, 'name'));
        }
    }

    private function canvasVector(mixed $v, string $name): void
    {
        $def = Definition::read(Json::get($v, 'definition'));
        $recorded = array_map(static fn (mixed $r): string => Json::str($r, 'id'), get_object_vars(Json::object(Json::get($v, 'recorded'))));
        $live = self::live(Json::get($v, 'live'));
        $kept = Plan::stillLive($def, $recorded, $live);
        $expect = Json::get($v, 'expect');
        try {
            $ops = Plan::build($def, $kept);
        } catch (Refusal $r) {
            $this->assertSame(Json::get($expect, 'refused'), $r->handle, $name);
            return;
        }
        $this->assertEquals(Json::object(Json::get($expect, 'kept')), (object) $kept, "{$name}: kept");
        $this->assertEquals(Json::get($expect, 'ops'), Json::decode(Json::encode($ops)), "{$name}: ops");
        if (!is_array(Json::get($v, 'verdicts'))) {
            return;
        }
        $verdicts = Json::items($v, 'verdicts');
        try {
            $steps = Steps::of($ops, $verdicts, $live, $kept);
            $this->assertEquals(Json::get($v, 'steps'), array_map([self::class, 'rendered'], $steps), "{$name}: steps");
        } catch (Stop $stop) {
            $want = $stop->replace !== null ? ['replace' => $stop->replace] : ['refused' => array_column($stop->refused, 0)];
            $this->assertEquals(Json::get($v, 'stop'), (object) $want, "{$name}: stop");
        }
    }

    private function b1Table(): void
    {
        $text = TableRender::render(['ID', 'NAME', 'SERVING'], [['7d1e', 'Flashcards', '1.0.0'], ['7d1e2f30-long', 'Tone drills', '']]);
        $this->assertSame("ID             NAME         SERVING\n7d1e           Flashcards   1.0.0\n7d1e2f30-long  Tone drills  -\n", $text);
        $this->assertSame("SCOPES  B\na       b\n", TableRender::render(['SCOPES', 'B'], [['a', 'b']]));
        $this->assertSame('a:read,b:write', TableRender::cell(['a:read', 'b:write']));
        $this->assertSame('-', TableRender::cell([]));
        $this->assertSame('true', TableRender::cell(true));
        $this->assertSame('{"a":1}', TableRender::cell((object) ['a' => 1]));
        $this->assertSame([['id', 'x'], ['name', '-']], TableRender::fields((object) ['id' => 'x', 'name' => null]));
    }

    /** Ctrl+C inside a session is a flag the loop polls, so it cleans up and **returns** 130, never dies by signal (ADR 16.10.26af D9). */
    private function interruptedReturns130(): void
    {
        Signals::install(static fn (string $ext): bool => extension_loaded($ext));
        Signals::trap();
        try {
            posix_kill((int) getmypid(), SIGINT);
            $this->assertTrue(Signals::stopped());
            $kit = self::kit();
            $ctx = new \Lingara\Cli\Core\Ctx($kit->app, new \Lingara\Cli\Core\Output(static function (string $t): void {
            }, false), 'default');
            $session = ['id' => 's1', 'lease_secs' => 300, 'expires_at' => '', 'app_secret' => '', 'client_secret' => null];
            DevHold::hold(new RunState($ctx, ['name' => 'luba', 'id' => 'a1'], $session));
            $this->fail('the session went on after SIGINT');
        } catch (Fail $fail) {
            $this->assertSame(130, $fail->exit->value);
            $this->assertSame('interrupted', $fail->exit->label());
        } finally {
            Signals::release();
        }
    }

    private function usageRefusals(): void
    {
        $argvs = [['-q'], ['-v'], ['-n'], ['list'], ['app:li'], ['apps', 'li'], ['apps'], ['client', 'list', '--nope'], ['client', 'list', '-q'], ['nope']];
        foreach ($argvs as $argv) {
            $kit = self::kit();
            $outcome = $kit->run($argv);
            $this->assertSame(ExitCode::Usage, $outcome->exit, implode(' ', $argv));
            $this->assertSame('', $outcome->stdout);
            $this->assertSame([], $kit->transport->sent, implode(' ', $argv) . ' reached no server');
        }
        $kit = self::kit();
        $this->assertSame(ExitCode::Usage, $kit->run(['client', 'create', '--name', 'x', '--ci', '--type', 'public', '--json'])->exit);
        $this->assertSame(2, Json::get(Json::get(Json::decode(trim($kit->stderr)), 'error'), 'exit'));
    }

    /** Through the `extension_loaded` seam: with no ext-pcntl, `apps dev` is `tool_missing`, before it reads anything. */
    private function appsDevNeedsPcntl(): void
    {
        $kit = new Kit(new FakeTransport(), new FakeTerminal(), [], ['pcntl' => false]);
        $outcome = $kit->run(['apps', 'dev']);
        $this->assertSame(ExitCode::ToolMissing, $outcome->exit);
        $this->assertSame('tool_missing_php_ext', $outcome->fail?->errorCode);
        $this->assertStringContainsString('lingara apps dev needs the PHP extensions ext-pcntl to stop', $kit->stderr);
        $this->assertSame([], $kit->transport->sent);
    }

    private function versionIsTheRepositorys(): void
    {
        $kit = self::kit();
        $this->assertSame('lingara ' . Version::VERSION . " (php)\n", $kit->run(['--version'])->stdout);
        $repo = dirname(__DIR__, 2) . '/VERSION';
        if (is_file($repo)) {
            $this->assertSame(trim((string) file_get_contents($repo)), Version::VERSION, 'src/Version.php is held equal to VERSION');
        }
    }
}
