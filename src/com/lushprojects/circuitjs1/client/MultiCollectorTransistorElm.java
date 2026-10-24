/*
    Copyright (C) Paul Falstad and Iain Sharp

    This file is part of CircuitJS1.

    CircuitJS1 is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 2 of the License, or
    (at your option) any later version.

    CircuitJS1 is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with CircuitJS1.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.lushprojects.circuitjs1.client;

import com.lushprojects.circuitjs1.client.util.Locale;

import com.google.gwt.xml.client.Element;
import com.google.gwt.xml.client.Document;

// A multi-collector BJT: N independent base-collector junctions sharing one
// base and one emitter -- the mirror image of MultiEmitterTransistorElm.
// Motivating real-world case: some 3-terminal packaged parts (e.g. certain
// regulators) bring out a lead more than once for current capacity, giving
// pinouts like "BEEC"/"BECC" -- this models the genuinely-separate-junction
// end of that spectrum (an actual multi-collector die), built the same
// CompositeElm-of-NTransistorElm way as MultiEmitterTransistorElm: one
// ordinary transistor per collector, all sharing base+emitter.
//
// Pin order is physical-package order E-B-n*C ("EBnC"). Number of
// collectors and Beta/hFE (applied uniformly to every collector) are both
// edit-box options.
public class MultiCollectorTransistorElm extends CompositeElm {

    static final int MIN_COLLECTORS = 1;
    static final int MAX_COLLECTORS = 8;
    static final int DEFAULT_COLLECTORS = 3;

    int count;           // number of collectors (n in "EBnC")
    int pnp;              // +1 for NPN, -1 for PNP
    double beta = 100;     // applied to all collector sub-transistors uniformly

    int emitterPinIndex, basePinIndex, collectorStartIndex;
    String[] pinLabels;
    int NUM_PINS;

    private Polygon rectPoly;
    private Point pinAttach[], pinPost[];
    private double curcount[];

    MultiCollectorTransistorElm(int xx, int yy, boolean pnpflag) {
        super(xx, yy, buildModelString(DEFAULT_COLLECTORS), buildExternalNodes(DEFAULT_COLLECTORS));
        count = DEFAULT_COLLECTORS;
        pnp = pnpflag ? -1 : 1;
        setSubPnp();
        setSubBeta();
        setupLabels();
        noDiagonal = true;
    }

    public MultiCollectorTransistorElm(int xa, int ya, int xb, int yb, int f, StringTokenizer st) {
        super(xa, ya, xb, yb, f, st, buildModelString(DEFAULT_COLLECTORS), buildExternalNodes(DEFAULT_COLLECTORS));
        count = DEFAULT_COLLECTORS;
        pnp = new Integer(st.nextToken()).intValue();
        setSubPnp();
        setupLabels();
        noDiagonal = true;
    }

    // internal node numbers: 1=base, 2=emitter, 3..(2+n)=collectors
    private static String buildModelString(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            if (i > 1)
                sb.append('\r');
            sb.append("NTransistorElm 1 ").append(2 + i).append(" 2");
        }
        return sb.toString();
    }

    // external post order is EBnC (physical pinout): emitter, base, then collectors
    private static int[] buildExternalNodes(int n) {
        int[] ext = new int[n + 2];
        ext[0] = 2; // emitter
        ext[1] = 1; // base
        for (int i = 0; i != n; i++)
            ext[2 + i] = 3 + i;
        return ext;
    }

    private void setupLabels() {
        NUM_PINS = count + 2;
        emitterPinIndex = 0;
        basePinIndex = 1;
        collectorStartIndex = 2;
        pinLabels = new String[NUM_PINS];
        pinLabels[emitterPinIndex] = "E";
        pinLabels[basePinIndex] = "B";
        for (int i = 0; i != count; i++)
            pinLabels[collectorStartIndex + i] = "C";
    }

    private void setSubPnp() {
        for (int i = 0; i != count; i++)
            ((TransistorElm) compElmList.get(i)).pnp = pnp;
    }

    private void setSubBeta() {
        for (int i = 0; i != count; i++)
            ((TransistorElm) compElmList.get(i)).setBeta(beta);
    }

    // Rebuild the composite from scratch for a new collector count,
    // preserving pnp/beta -- see MultiEmitterTransistorElm.rebuild() for why
    // each of these steps is needed and in this order.
    private void rebuild(int newCount) {
        count = newCount;
        loadComposite(null, buildModelString(count), buildExternalNodes(count));
        setSubPnp();
        setSubBeta();
        setupLabels();
        buildCompNodeList();
        allocNodes();
        setPoints();
    }

    public void reset() {
        super.reset();
        curcount = new double[NUM_PINS];
    }

    public int getDumpType() { return 441; }

    public String dump() {
        return super.dump() + " " + pnp;
    }

    String getXmlDumpType() { return "mct"; }

    void dumpXml(Document doc, Element elem) {
        super.dumpXml(doc, elem);
        XMLSerializer.dumpAttr(elem, "pnp", pnp);
        XMLSerializer.dumpAttr(elem, "nc", count);
    }

    void undumpXml(XMLDeserializer xml) {
        // Collector count is structural -- see MultiEmitterTransistorElm's
        // undumpXml() for why this has to happen before super.undumpXml().
        int newCount = xml.parseIntAttr("nc", count);
        if (newCount != count)
            rebuild(newCount);
        super.undumpXml(xml);
        pnp = xml.parseIntAttr("pnp", pnp);
        setSubPnp();
        beta = ((TransistorElm) compElmList.get(0)).beta;
    }

    String getElmType() { return "multi-collector transistor"; }

    void getInfo(String arr[]) {
        arr[0] = Locale.LS("multi-collector transistor") + " (" + ((pnp == -1) ? "PNP)" : "NPN)");
        arr[1] = count + " collectors, shared base/emitter, β=" + showFormat.format(beta);
        arr[2] = "Ie = " + getCurrentText(-getCurrentIntoNode(emitterPinIndex));
        arr[3] = "Ib = " + getCurrentText(-getCurrentIntoNode(basePinIndex));
        int i;
        for (i = 0; i != count; i++)
            arr[4 + i] = "Ic" + (i + 1) + " = " + getCurrentText(-getCurrentIntoNode(collectorStartIndex + i));
    }

    public EditInfo getEditInfo(int n) {
        if (n == 0)
            return new EditInfo("# of Collectors", count, MIN_COLLECTORS, MAX_COLLECTORS).setDimensionless();
        if (n == 1)
            return new EditInfo("Beta/hFE", beta, 10, 1000).setDimensionless();
        return null;
    }

    public void setEditValue(int n, EditInfo ei) {
        if (n == 0) {
            int newCount = (int) ei.value;
            if (newCount < MIN_COLLECTORS || newCount > MAX_COLLECTORS) {
                ei.setError("must be " + MIN_COLLECTORS + " to " + MAX_COLLECTORS);
                return;
            }
            if (newCount != count)
                rebuild(newCount);
        }
        if (n == 1) {
            beta = ei.value;
            setSubBeta();
        }
    }

    void setPoints() {
        super.setPoints();
        int bodyHalfHeight = 8;
        int leadLen = 16;
        int i;
        pinAttach = newPointArray(NUM_PINS);
        pinPost = newPointArray(NUM_PINS);
        for (i = 0; i != NUM_PINS; i++) {
            double frac = (i + 1.0) / (NUM_PINS + 1);
            pinAttach[i] = interpPoint(point1, point2, frac, -bodyHalfHeight * dsign);
            pinPost[i]   = interpPoint(point1, point2, frac, -(bodyHalfHeight + leadLen) * dsign);
            setPost(i, pinPost[i]);
        }
        Point bodyTL = interpPoint(point1, point2, 0, -bodyHalfHeight * dsign);
        Point bodyTR = interpPoint(point1, point2, 1, -bodyHalfHeight * dsign);
        Point bodyBR = interpPoint(point1, point2, 1,  bodyHalfHeight * dsign);
        Point bodyBL = interpPoint(point1, point2, 0,  bodyHalfHeight * dsign);
        rectPoly = createPolygon(bodyTL, bodyTR, bodyBR, bodyBL);
    }

    void draw(Graphics g) {
        setBbox(point1, point2, 8 + 16);
        int i;
        if (curcount == null || curcount.length != NUM_PINS)
            curcount = new double[NUM_PINS];
        for (i = 0; i != NUM_PINS; i++) {
            setVoltageColor(g, volts[i]);
            drawThickLine(g, pinAttach[i], pinPost[i]);
            curcount[i] = updateDotCount(getCurrentIntoNode(i), curcount[i]);
            drawDots(g, pinAttach[i], pinPost[i], curcount[i]);
        }
        setPowerColor(g, true);
        g.setColor(needsHighlight() ? selectColor : lightGrayColor);
        g.fillPolygon(rectPoly);

        if (needsHighlight() || isCreating()) {
            g.setColor(whiteColor);
            for (i = 0; i != NUM_PINS; i++) {
                if (dy == 0)
                    g.drawString(pinLabels[i], pinPost[i].x - 3, pinPost[i].y - dsign * 8);
                else
                    g.drawString(pinLabels[i], pinPost[i].x - dsign * 8, pinPost[i].y + 4);
            }
        }
        drawPosts(g);
    }
}
