#!/usr/bin/env node
// `lingara`, the TypeScript kit (ADR 16.10.26ad D10). The command tree is
// oclif's; dispatch, output and exit codes are the kit's own `main`, so an
// in-process verb (canvas apply, D7) runs through the same entry point.
// `main` has flushed stdout by the time it returns, so exiting at once is
// safe, and no idle keep-alive socket or stdin reader can hold the process.
import { main } from '../dist/lib/main.js'

process.exit(await main(process.argv.slice(2)))
