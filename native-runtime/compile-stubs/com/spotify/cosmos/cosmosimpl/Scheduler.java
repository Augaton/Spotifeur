package com.spotify.cosmos.cosmosimpl;
public interface Scheduler { void post(Runnable callback); boolean isOnSchedulerThread(); }
