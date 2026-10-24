package com.lushprojects.circuitjs1.client;

public class NMultiCollectorTransistorElm extends MultiCollectorTransistorElm {

    public NMultiCollectorTransistorElm(int xx, int yy) {
	super(xx, yy, false);
    }

    Class getDumpClass() {
	return MultiCollectorTransistorElm.class;
    }
}
