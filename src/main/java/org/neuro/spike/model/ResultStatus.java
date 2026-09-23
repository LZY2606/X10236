package org.neuro.spike.model;

/**
 * The three mandated failure families are deliberately distinct from OK:
 * INCOMPLETE  -> an input is missing, carry a positional diagnostic
 * CORRUPT     -> bytes exist but are malformed/undecodable
 * UNDECIDABLE -> inputs are complete and valid, but no unique answer exists
 * BUDGET_EXHAUSTED -> the declared compute budget ran out before completion
 *
 * None of the failure states may be disguised with nulls or defaults.
 */
public enum ResultStatus {
    OK,
    INCOMPLETE,
    CORRUPT,
    UNDECIDABLE,
    BUDGET_EXHAUSTED
}
