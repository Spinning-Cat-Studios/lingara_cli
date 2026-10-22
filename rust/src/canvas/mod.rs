//! Canvas apply, matryoshka-style (ADR 16.10.26ad D7; contract B7). A draft
//! creates nothing; `apply` plans in the web planner's order, dry-runs the
//! plan, then runs **this CLI's own verbs** in-process, one per op, records
//! each object's `runtime_id` and activates.
//!
//! `plan` and `argv` are pure, and both are pinned by
//! `conformance/vectors/canvas-plans.json`, which every kit's tests read:
//! nothing ties this planner to the web's `plan.ts` mechanically, so a
//! change there updates the vectors by hand.

pub mod apply;
pub mod argv;
pub mod definition;
pub mod execute;
pub mod plan;
pub mod spec;

#[cfg(test)]
#[path = "plan_tests.rs"]
mod plan_tests;
