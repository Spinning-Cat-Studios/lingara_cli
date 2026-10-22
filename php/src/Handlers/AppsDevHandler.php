<?php

declare(strict_types=1);

namespace Lingara\Cli\Handlers;

use Lingara\Cli\Console\Input;
use Lingara\Cli\Core\Ctx;
use Lingara\Cli\Core\Json;
use Lingara\Cli\Dev\DevRun;
use Lingara\Cli\Dev\Preflight;
use Lingara\Cli\Project\ProjectManifest;
use Lingara\Cli\Project\Rows;

/**
 * `lingara apps dev [name]` (ADR 16.10.26ae D7): this kit's platform
 * refusals first (ADR 16.10.26af D2), then resolve the row and run every
 * refusal offline, so a refused run sends no request; then register a row
 * with no id (D6 step 4) and hand over to the session. `--reset` is one
 * `PATCH {render_url: null}` and nothing else.
 */
final class AppsDevHandler
{
    public static function dev(Ctx $ctx, Input $in): void
    {
        Preflight::platform($ctx);
        [$toml, $cwd] = Rows::open($ctx, $in->opt('config'));
        $row = Rows::resolve($toml, $in->opt('name'), $cwd);
        if ($in->flag('reset')) {
            Preflight::reset($ctx, $row);
            return;
        }
        $plan = Preflight::plan($ctx, $in, $toml, $row, $cwd);
        if ($row['id'] === null) {
            $app = ProjectManifest::register($ctx, $toml, $row, ProjectManifest::read($ctx, $toml, $row, $cwd));
            // The registration printed the app; `apps dev` prints one value, at its exit.
            $ctx->out->stdout = '';
            $plan['id'] = Json::str($app, 'id');
        }
        DevRun::session($ctx, $plan);
    }
}
