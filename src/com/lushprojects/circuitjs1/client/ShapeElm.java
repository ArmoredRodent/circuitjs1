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

import com.google.gwt.canvas.dom.client.Context2d;
import com.google.gwt.core.client.JavaScriptObject;
import com.google.gwt.core.client.JsArrayNumber;
import com.google.gwt.xml.client.Document;
import com.google.gwt.xml.client.Element;

// Common base for the editable drawing shapes (BoxElm, LineElm): colour, thickness and
// line style, plus a "last used" memory so a new shape copies the one most recently drawn
// or edited.  Snap mode is a session setting shared by all shapes and isn't saved.
// Edit dialog rows: the 3 common rows, the subclass's own rows (getExtraEditInfo), then
// the 2 snap checkboxes.
abstract class ShapeElm extends GraphicElm {

    static final int SNAP_FREE = 0, SNAP_GRID = 1, SNAP_MID = 2;
    static final int STYLE_SOLID = 0, STYLE_DASHED = 1, STYLE_DOTTED = 2,
	STYLE_LONG_DASH = 3, STYLE_DASH_DOT = 4, STYLE_DASH_DOT_DOT = 5;
    static final String styleNames[] = { "Solid", "Dashed", "Dotted", "Long Dash", "Dash-Dot", "Dash-Dot-Dot" };

    // dash/gap patterns for thickness 1-2; they step up every 2 thicknesses (x2 at 3-4,
    // x3 at 5-6...).  Line ends are round, so a near-zero dash draws a round dot.
    static final double DOT = .01;
    static final double dashPatterns[][] = {
	null, { 16, 6 }, { DOT, 4 }, { 32, 10 }, { 16, 5, DOT, 5 }, { 16, 5, DOT, 5, DOT, 5 } };

    String color;	// null = default gray
    int thickness = 1;
    int lineStyle;

    // session settings, not saved: snap mode, and whether Shift is held while drawing
    // (locks a line to 0/45/90 degrees, a box to a square)
    static int snap = SNAP_GRID;
    static boolean shiftLock;

    // memory of the last shape drawn or edited, shared by boxes and lines
    static String lastColor;
    static int lastThickness = 1;
    static int lastStyle = -1;	// -1 = each shape's own default (box dashed, line solid)

    // the two snap checkboxes in the open edit dialog, so ticking one can untick the other
    Checkbox gridBox, midBox;

    // new shape being drawn (also used when loading, see undumpXml): copy the last one
    ShapeElm(int xx, int yy) {
	super(xx, yy);
	color = lastColor;
	thickness = lastThickness;
	lineStyle = (lastStyle < 0) ? defaultStyle() : lastStyle;
    }

    ShapeElm(int xa, int ya, int xb, int yb, int f) {
	super(xa, ya, xb, yb, f);
	lineStyle = defaultStyle();
    }

    abstract int defaultStyle();

    void remember() {
	lastColor = color;
	lastThickness = thickness;
	lastStyle = lineStyle;
    }

    void draggingDone() { remember(); }

    // ---- snapping and drawing out ----

    int snapCoord(int v) {
	if (snap == SNAP_GRID)
	    return app.snapGrid(v);
	if (snap == SNAP_MID) {
	    int h = app.gridSize/2;
	    return app.snapGrid(v-h)+h;
	}
	return v;
    }

    // first point of a new shape, from the raw (unsnapped) mouse position
    void placeStart(int xx, int yy) {
	x = x2 = snapCoord(xx);
	y = y2 = snapCoord(yy);
	setBbox(x, y, x2, y2);
    }

    void drag(int xx, int yy) {
	x2 = snapCoord(xx);
	y2 = snapCoord(yy);
	if (shiftLock)
	    constrain();
    }

    // Shift held while drawing; each shape decides what that means
    void constrain() { }

    // drag one endpoint to a raw mouse position
    void movePostTo(int n, int xx, int yy) {
	xx = snapCoord(xx);
	yy = snapCoord(yy);
	if (n == 0 ? (xx == x2 && yy == y2) : (xx == x && yy == y))
	    return;	// don't collapse to zero size
	if (n == 0) { x = xx; y = yy; } else { x2 = xx; y2 = yy; }
	setBbox(x, y, x2, y2);
	setPoints();
    }

    // shapes have no posts, but both stored points are resize handles
    int getNumHandles() { return 2; }

    // show both handles, the grabbed one bigger
    void drawHandles(Graphics g, Color c) {
	g.setColor(c);
	if (lastHandleGrabbed == 0)
	    g.fillRect(x-4, y-4, 9, 9);
	else
	    g.fillRect(x-3, y-3, 7, 7);
	if (lastHandleGrabbed == 1)
	    g.fillRect(x2-4, y2-4, 9, 9);
	else
	    g.fillRect(x2-3, y2-3, 7, 7);
    }

    // ---- drawing helpers ----

    // set colour, width and dash for stroking; call endStroke() afterwards
    void beginStroke(Graphics g) {
	g.setColor(needsHighlight() ? selectColor : shapeColor());
	g.setLineWidth(thickness);
	double p[] = dashPatterns[lineStyle];
	if (p != null) {
	    int step = (thickness+1)/2;
	    JsArrayNumber a = JavaScriptObject.createArray().cast();
	    for (double v : p)
		a.push(v*step);
	    setDash(g.context, a);
	}
    }

    void endStroke(Graphics g) {
	g.setLineDash(0, 0);
	g.setLineWidth(1.0);
    }

    // Graphics.setLineDash() only takes one dash/gap pair
    static native void setDash(Context2d ctx, JsArrayNumber a) /*-{
	ctx.setLineDash(a);
    }-*/;

    Color shapeColor() { return color != null ? new Color(color) : Color.GRAY; }

    // ---- edit dialog ----

    int extraCount() { return 0; }
    EditInfo getExtraEditInfo(int n) { return null; }
    void setExtraEditValue(int n, EditInfo ei) { }

    public EditInfo getEditInfo(int n) {
	if (n == 0)
	    return new EditInfo("Color", color != null ? color : Color.GRAY.getHexValue()).setIsColor();
	if (n == 1)
	    return new EditInfo("Thickness", thickness, 1, 10).setDimensionless().setUnitStep().disallowSliders();
	if (n == 2) {
	    EditInfo ei = new EditInfo("Line Style", 0, -1, -1);
	    ei.choice = new Choice();
	    for (String s : styleNames)
		ei.choice.add(s);
	    ei.choice.select(lineStyle);
	    return ei;
	}
	n -= 3;
	if (n < extraCount())
	    return getExtraEditInfo(n);
	n -= extraCount();
	// neither ticked = free placement.  only affects what's drawn or moved from now on
	if (n == 0) {
	    EditInfo ei = EditInfo.createCheckbox("Snap to Grid", snap == SNAP_GRID);
	    gridBox = ei.checkbox;
	    return ei;
	}
	if (n == 1) {
	    EditInfo ei = EditInfo.createCheckbox("Snap to Mid-Grid", snap == SNAP_MID);
	    midBox = ei.checkbox;
	    return ei;
	}
	return null;
    }

    public void setEditValue(int n, EditInfo ei) {
	if (n == 0) {
	    String c = ei.textf.getText();
	    color = c.equalsIgnoreCase(Color.GRAY.getHexValue()) ? null : c;
	} else if (n == 1) {
	    int t = (int) Math.round(ei.value);
	    if (t < 1 || t > 10) {
		ei.setError("must be from 1 to 10");
		return;
	    }
	    thickness = t;
	} else if (n == 2) {
	    lineStyle = ei.choice.getSelectedIndex();
	} else if (n-3 < extraCount()) {
	    setExtraEditValue(n-3, ei);
	} else {
	    int k = n-3-extraCount();
	    int mode = (k == 0) ? SNAP_GRID : SNAP_MID;
	    if (ei.checkbox.getState()) {
		snap = mode;
		Checkbox other = (k == 0) ? midBox : gridBox;
		if (other != null)
		    other.setState(false);	// no event fired, so no loop
	    } else if (snap == mode)
		snap = SNAP_FREE;
	}
	remember();
    }

    // ---- save/load (attributes left out when they're the default) ----

    void dumpXml(Document doc, Element elem) {
	super.dumpXml(doc, elem);
	if (color != null)
	    XMLSerializer.dumpAttr(elem, "co", color);
	if (thickness != 1)
	    XMLSerializer.dumpAttr(elem, "th", thickness);
	if (lineStyle != defaultStyle())
	    XMLSerializer.dumpAttr(elem, "ls", lineStyle);
    }

    void undumpXml(XMLDeserializer xml) {
	super.undumpXml(xml);
	// loading goes through the new-shape constructor, which copied the remembered
	// settings; a missing attribute means the default, not the remembered value
	color = xml.parseStringAttr("co", null);
	thickness = xml.parseIntAttr("th", 1);
	lineStyle = xml.parseIntAttr("ls", defaultStyle());
    }

    void getInfo(String arr[]) { }

    @Override
    int getShortcut() { return 0; }
}
