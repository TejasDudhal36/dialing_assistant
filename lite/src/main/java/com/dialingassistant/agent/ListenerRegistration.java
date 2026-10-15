package com.dialingassistant.agent;

public final class ListenerRegistration {
    private final Runnable removeAction;

    public ListenerRegistration(Runnable removeAction) {
        this.removeAction = removeAction;
    }

    public void remove() {
        if (removeAction != null) {
            removeAction.run();
        }
    }
}
