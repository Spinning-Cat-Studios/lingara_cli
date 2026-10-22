// D4's two git questions (ADR 16.10.26ae): is a file tracked (a warning),
// and does git ignore a path that would hold secrets (a refusal). Outside a
// work tree, or with no `git` on PATH, there is no index to commit a file
// into, so both answers are the permissive ones and the checks are skipped.
import { spawnSync } from 'node:child_process'
import { basename, dirname } from 'node:path'

const git = (dir: string, args: string[]): boolean => spawnSync('git', ['-C', dir, ...args], { stdio: 'ignore' }).status === 0

export const inWorkTree = (dir: string): boolean => git(dir, ['rev-parse', '--is-inside-work-tree'])

/** Whether git tracks `path`; false outside a work tree. */
export const tracked = (path: string): boolean => inWorkTree(dirname(path)) && git(dirname(path), ['ls-files', '--error-unmatch', '--', basename(path)])

/** Whether git ignores `path`; true outside a work tree, where nothing can be committed. */
export const ignored = (path: string): boolean => !inWorkTree(dirname(path)) || git(dirname(path), ['check-ignore', '-q', '--', basename(path)])
