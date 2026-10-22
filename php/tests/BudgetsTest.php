<?php

declare(strict_types=1);

namespace Lingara\Cli\Tests;

use PHPUnit\Framework\TestCase;

/**
 * The budgets PHPCS has no sniff for, at the repository's numbers
 * (.code-health/thresholds.toml): at most five parameters per function,
 * fifteen functions per file and fifteen files per folder, and a test file
 * at most 600 lines. phpcs.xml holds the other three.
 */
final class BudgetsTest extends TestCase
{
    private const MAX_PARAMS = 5;
    private const MAX_FUNCTIONS = 15;
    private const MAX_FILES = 15;
    private const MAX_TEST_LINES = 600;

    /** @return list<string> every PHP file under `$dir` */
    private static function files(string $dir): array
    {
        $out = [];
        foreach (new \RecursiveIteratorIterator(new \RecursiveDirectoryIterator($dir, \FilesystemIterator::SKIP_DOTS)) as $file) {
            if ($file instanceof \SplFileInfo && $file->getExtension() === 'php') {
                $out[] = $file->getPathname();
            }
        }
        sort($out);
        return $out;
    }

    public function testParametersAndFunctionsPerFile(): void
    {
        foreach (self::files(dirname(__DIR__) . '/src') as $path) {
            $tokens = array_values(\PhpToken::tokenize((string) file_get_contents($path)));
            $functions = 0;
            foreach ($tokens as $i => $token) {
                if (!$token->is(T_FUNCTION)) {
                    continue;
                }
                $functions++;
                $this->assertLessThanOrEqual(self::MAX_PARAMS, self::parameters($tokens, $i), "{$path}: a function on line {$token->line} takes more than five parameters");
            }
            $this->assertLessThanOrEqual(self::MAX_FUNCTIONS, $functions, "{$path} holds more than fifteen functions");
        }
    }

    /**
     * The parameters of the function whose `function` keyword is token `$at`:
     * the variables inside its first parenthesised list, at depth one.
     *
     * @param list<\PhpToken> $tokens
     */
    private static function parameters(array $tokens, int $at): int
    {
        [$depth, $count] = [0, 0];
        for ($i = $at + 1; $i < count($tokens); $i++) {
            $text = $tokens[$i]->text;
            $depth += ($text === '(' ? 1 : 0) - ($text === ')' ? 1 : 0);
            if ($text === ')' && $depth === 0) {
                return $count;
            }
            $count += $depth === 1 && $tokens[$i]->is(T_VARIABLE) ? 1 : 0;
        }
        return $count;
    }

    public function testFilesPerFolderAndTestLength(): void
    {
        $root = dirname(__DIR__);
        foreach (['src', 'tests'] as $top) {
            $dirs = [$root . '/' . $top];
            foreach (new \RecursiveIteratorIterator(new \RecursiveDirectoryIterator($root . '/' . $top, \FilesystemIterator::SKIP_DOTS), \RecursiveIteratorIterator::SELF_FIRST) as $entry) {
                if ($entry instanceof \SplFileInfo && $entry->isDir()) {
                    $dirs[] = $entry->getPathname();
                }
            }
            foreach ($dirs as $dir) {
                $this->assertLessThanOrEqual(self::MAX_FILES, count(glob($dir . '/*') ?: []), "{$dir} holds more than fifteen entries");
            }
        }
        foreach (self::files($root . '/tests') as $path) {
            $this->assertLessThanOrEqual(self::MAX_TEST_LINES, count(file($path) ?: []), "{$path} is longer than 600 lines");
        }
    }
}
