package com.jobcopilot.matching;
public class MatchCannotBeComputedException extends RuntimeException {
    public MatchCannotBeComputedException() { super("Job needs at least one skill requirement or a positive minimum experience to compute a match"); }
}

