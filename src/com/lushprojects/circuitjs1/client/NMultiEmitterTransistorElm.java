package com.lushprojects.circuitjs1.client;

public class NMultiEmitterTransistorElm extends MultiEmitterTransistorElm {

    public NMultiEmitterTransistorElm(int xx, int yy) {
	super(xx, yy, false);
    }

    Class getDumpClass() {
	return MultiEmitterTransistorElm.class;
    }
}
