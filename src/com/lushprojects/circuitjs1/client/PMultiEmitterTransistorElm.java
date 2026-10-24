package com.lushprojects.circuitjs1.client;

public class PMultiEmitterTransistorElm extends MultiEmitterTransistorElm {

    public PMultiEmitterTransistorElm(int xx, int yy) {
	super(xx, yy, true);
    }

    Class getDumpClass() {
	return MultiEmitterTransistorElm.class;
    }
}
