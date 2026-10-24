package com.lushprojects.circuitjs1.client;

public class PMultiCollectorTransistorElm extends MultiCollectorTransistorElm {

    public PMultiCollectorTransistorElm(int xx, int yy) {
	super(xx, yy, true);
    }

    Class getDumpClass() {
	return MultiCollectorTransistorElm.class;
    }
}
