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

// A true multi-emitter BJT: N independent base-emitter junctions sharing one
// base and one collector, e.g. the input transistor of a classic TTL gate.
// Built the same way DarlingtonElm is -- as a CompositeElm made of ordinary
// NTransistorElm instances -- rather than as a hand-written nonlinear model,
// since that's exactly how a real multi-emitter transistor is represented
// when you don't have a native multi-emitter SPICE primitive: N single-
// emitter BJTs sharing a base and collector node.
//
// Pin order is physical-package order n*E-B-C ("nEBC"), left to right along
// however the element is dragged out, matching how a real multi-lead part's
// datasheet pinout would be drawn -- not the base/collector/emitter post
// order TransistorElm itself uses internally. Number of emitters and
// Beta/hFE (applied uniformly to every emitter) are both edit-box options.
public class MultiEmitterTransistorElm extends CompositeElm {

    static final int MIN_EMITTERS = 1;
    static final int MAX_EMITTERS = 8;
    static final int DEFAULT_EMITTERS = 3;

    int count;          // number of emitters (n in "nEBC")
    int pnp;             // +1 for NPN, -1 for PNP
    double beta = 100;    // applied to all emitter sub-transistors uniformly

    int basePinIndex, collectorPinIndex; // indices in to pinLabels/pinPost/pinAttach
    String[] pinLabels;
    int NUM_PINS;

    private Polygon rectPoly;
    private Point pinAttach[], pinPost[];
    private double curcount[];

    MultiEmitterTransistorElm(int xx, int yy, boolean pnpflag) {
        super(xx, yy, buildModelString(DEFAULT_EMITTERS), buildExternalNodes(DEFAULT_EMITTERS));
        count = DEFAULT_EMITTERS;
        pnp = pnpflag ? -1 : 1;
        setSubPnp();
        setSubBeta();
        setupLabels();
        noDiagonal = true;
    }

    public MultiEmitterTransistorElm(int xa, int ya, int xb, int yb, int f, StringTokenizer st) {
        super(xa, ya, xb, yb, f, st, buildModelString(DEFAULT_EMITTERS), buildExternalNodes(DEFAULT_EMITTERS));
        count = DEFAULT_EMITTERS;
        pnp = new Integer(st.nextToken()).intValue();
        setSubPnp();
        setupLabels();
        noDiagonal = true;
    }

    // internal node numbers: 1=base, 2=collector, 3..(2+n)=emitters
    private static String buildModelString(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            if (i > 1)
                sb.append('\r');
            sb.append("NTransistorElm 1 2 ").append(2 + i);
        }
        return sb.toString();
    }

    // external post order is nEBC (physical pinout): emitters, then base, then collector
    private static int[] buildExternalNodes(int n) {
        int[] ext = new int[n + 2];
        for (int i = 0; i != n; i++)
            ext[i] = 3 + i;
        ext[n] = 1;     // base
        ext[n + 1] = 2; // collector
        return ext;
    }

    private void setupLabels() {
        NUM_PINS = count + 2;
        basePinIndex = count;
        collectorPinIndex = count + 1;
        pinLabels = new String[NUM_PINS];
        for (int i = 0; i != count; i++)
            pinLabels[i] = "E";
        pinLabels[basePinIndex] = "B";
        pinLabels[collectorPinIndex] = "C";
    }

    private void setSubPnp() {
        for (int i = 0; i != count; i++)
            ((TransistorElm) compElmList.get(i)).pnp = pnp;
    }

    private void setSubBeta() {
        for (int i = 0; i != count; i++)
            ((TransistorElm) compElmList.get(i)).setBeta(beta);
    }

    // Rebuild the composite from scratch for a new emitter count, preserving
    // pnp/beta. This replaces compElmList wholesale (loadComposite() below
    // constructs fresh NTransistorElm children), so anything derived from the
    // old child list -- node mapping, allocated nodes, lead geometry -- has
    // to be rebuilt too, in the same order the constructor builds it the
    // first time.
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

    public int getDumpType() { return 440; }

    public String dump() {
        return super.dump() + " " + pnp;
    }

    String getXmlDumpType() { return "met"; }

    void dumpXml(Document doc, Element elem) {
        super.dumpXml(doc, elem);
        XMLSerializer.dumpAttr(elem, "pnp", pnp);
        XMLSerializer.dumpAttr(elem, "ne", count);
    }

    void undumpXml(XMLDeserializer xml) {
        // Emitter count is structural -- the composite has to be rebuilt to
        // match *before* super.undumpXml() restores each child's own state
        // by index (ix), so read it first, off the same element super's
        // ix-loop will use.
        int newCount = xml.parseIntAttr("ne", count);
        if (newCount != count)
            rebuild(newCount);
        super.undumpXml(xml);
        pnp = xml.parseIntAttr("pnp", pnp);
        setSubPnp();
        // Pick up the (uniform) beta that the per-child XML restore above
        // just set on emitter 0, so the edit box and any later rebuild()
        // reflect the real, just-loaded value rather than the default.
        beta = ((TransistorElm) compElmList.get(0)).beta;
    }

    String getElmType() { return "multi-emitter transistor"; }

    void getInfo(String arr[]) {
        arr[0] = Locale.LS("multi-emitter transistor") + " (" + ((pnp == -1) ? "PNP)" : "NPN)");
        arr[1] = count + " emitters, shared base/collector, β=" + showFormat.format(beta);
        arr[2] = "Ic = " + getCurrentText(-getCurrentIntoNode(collectorPinIndex));
        arr[3] = "Ib = " + getCurrentText(-getCurrentIntoNode(basePinIndex));
        int i;
        for (i = 0; i != count; i++)
            arr[4 + i] = "Ie" + (i + 1) + " = " + getCurrentText(-getCurrentIntoNode(i));
    }

    public EditInfo getEditInfo(int n) {
        if (n == 0)
            return new EditInfo("# of Emitters", count, MIN_EMITTERS, MAX_EMITTERS).setDimensionless();
        if (n == 1)
            return new EditInfo("Beta/hFE", beta, 10, 1000).setDimensionless();
        return null;
    }

    public void setEditValue(int n, EditInfo ei) {
        if (n == 0) {
            int newCount = (int) ei.value;
            if (newCount < MIN_EMITTERS || newCount > MAX_EMITTERS) {
                ei.setError("must be " + MIN_EMITTERS + " to " + MAX_EMITTERS);
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
        // dsign (set by super.setPoints()) is the perpendicular direction the
        // leads point, so the layout stays consistent regardless of which way
        // the element was dragged out.
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
            // match CompositeElm/DarlingtonElm's convention: getCurrentIntoNode()
            // is used unnegated for the dot-animation direction (body -> post),
            // and negated only for the textual Ic/Ib/Ie display in getInfo().
            curcount[i] = updateDotCount(getCurrentIntoNode(i), curcount[i]);
            drawDots(g, pinAttach[i], pinPost[i], curcount[i]);
        }
        setPowerColor(g, true);
        g.setColor(needsHighlight() ? selectColor : lightGrayColor);
        g.fillPolygon(rectPoly);

        if (needsHighlight() || isCreating()) {
            g.setColor(whiteColor);
            // first-cut label placement -- good enough for a horizontal or
            // vertical drag; not hand-tuned per letter like TransistorElm's.
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
