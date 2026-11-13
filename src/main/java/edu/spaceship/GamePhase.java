package edu.spaceship;

/** The phases through which the shared game state moves: LOBBY → ACTION → DISCUSSION → VOTING → ACTION (cycle) → FINISHED. */
public enum GamePhase { LOBBY, ACTION, DISCUSSION, VOTING, FINISHED }
