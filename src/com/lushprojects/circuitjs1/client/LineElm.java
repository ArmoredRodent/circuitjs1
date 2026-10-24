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

import com.google.gwt.xml.client.Document;
import com.google.gwt.xml.client.Element;

class LineElm extends ShapeElm {

    static final int ARROW_NONE = 0, ARROW_END = 1, ARROW_START = 2, ARROW_BOTH = 3;
    int arrows;

    // line-only memory of the last line drawn or edited
    static int lastArrows;

    // session setting, not saved: each new line starts where the last one ended
    static boolean daisyChain;

    public LineElm(int xx, int yy) {
	super(xx, yy);
	x2 = xx;
	y2 = yy;
	arrows = lastArrows;
	setBbox(x, y, x2, y2);
    }

    public LineElm(int xa, int ya, int xb, int yb, int f,
		   StringTokenizer st) {
	super(xa, ya, xb, yb, f);
	x2 = xb;
	y2 = yb;
	setBbox(x, y, x2, y2);
    }

    int getDumpType() { return 423; }
    int defaultStyle() { return STYLE_SOLID; }

    void remember() {
	super.remember();
	lastArrows = arrows;
    }

    boolean creationFailed() {
	return Math.hypot(x-x2, y-y2) < 16;
    }

    // Shift: lock to the nearest 0/45/90 degrees from the start point
    void constrain() {
	int dx = x2-x, dy = y2-y;
	if (dx == 0 && dy == 0)
	    return;
	int oct = (int) Math.round(Math.atan2(dy, dx)/(Math.PI/4));	// -4..4, in 45 degree steps
	if (oct % 4 == 0)
	    y2 = y;		// horizontal
	else if (Math.abs(oct) == 2)
	    x2 = x;		// vertical
	else {
	    int d = Math.max(Math.abs(dx), Math.abs(dy));
	    x2 = x + (dx < 0 ? -d : d);
	    y2 = y + (dy < 0 ? -d : d);
	}
    }
    
    void draw(Graphics g) {
	setBbox(x, y, x2, y2);
	double len = Math.hypot(x2-x, y2-y);
	boolean aStart = (arrows & ARROW_START) != 0;
	boolean aEnd = (arrows & ARROW_END) != 0;
	// arrowhead size grows with thickness; shorten the line so a thick
	// stroke doesn't poke out past the arrow's tip
	double alen = Math.min(6+3*thickness, len/2);
	int sx = x, sy = y, ex = x2, ey = y2;
	if (len > 0) {
	    double ux = (x2-x)/len, uy = (y2-y)/len;
	    if (aStart) { sx = (int) Math.round(x+ux*alen*.8); sy = (int) Math.round(y+uy*alen*.8); }
	    if (aEnd)   { ex = (int) Math.round(x2-ux*alen*.8); ey = (int) Math.round(y2-uy*alen*.8); }
	}
	beginStroke(g);
	g.drawLine(sx, sy, ex, ey);
	endStroke(g);
	if (len > 0) {
	    if (aStart)
		g.fillPolygon(arrowHead(x2, y2, x, y, len, alen));
	    if (aEnd)
		g.fillPolygon(arrowHead(x, y, x2, y2, len, alen));
	}
    }

    // filled arrowhead with its tip at (tx,ty), pointing away from (fx,fy)
    static Polygon arrowHead(int fx, int fy, int tx, int ty, double len, double alen) {
	double ux = (tx-fx)/len, uy = (ty-fy)/len;
	double bx = tx-ux*alen, by = ty-uy*alen;
	double hw = alen*.45;
	Polygon p = new Polygon();
	p.addPoint(tx, ty);
	p.addPoint((int) Math.round(bx-uy*hw), (int) Math.round(by+ux*hw));
	p.addPoint((int) Math.round(bx+uy*hw), (int) Math.round(by-ux*hw));
	return p;
    }

    int extraCount() { return 1; }

    EditInfo getExtraEditInfo(int n) {
	if (n == 0) {
	    EditInfo ei = new EditInfo("Arrowheads", 0, -1, -1);
	    ei.choice = new Choice();
	    ei.choice.add("None");
	    ei.choice.add("End");
	    ei.choice.add("Start");
	    ei.choice.add("Both Ends");
	    ei.choice.select(arrows);
	    return ei;
	}
	return null;
    }

    void setExtraEditValue(int n, EditInfo ei) {
	if (n == 0)
	    arrows = ei.choice.getSelectedIndex();
    }

    // daisy chain goes below the snap checkboxes
    int daisyRow() { return 3+extraCount()+2; }

    public EditInfo getEditInfo(int n) {
	if (n == daisyRow())
	    return EditInfo.createCheckbox("Daisy Chain", daisyChain);
	return super.getEditInfo(n);
    }

    public void setEditValue(int n, EditInfo ei) {
	if (n == daisyRow()) {
	    daisyChain = ei.checkbox.getState();
	    return;
	}
	super.setEditValue(n, ei);
    }

    void dumpXml(Document doc, Element elem) {
	super.dumpXml(doc, elem);
	if (arrows != ARROW_NONE)
	    XMLSerializer.dumpAttr(elem, "ar", arrows);
    }

    void undumpXml(XMLDeserializer xml) {
	super.undumpXml(xml);
	arrows = xml.parseIntAttr("ar", ARROW_NONE);
    }

    int getMouseDistance(int gx, int gy) {
	int thresh = Math.max(10, thickness/2+4);
        int d2 = lineDistanceSq(x, y, x2, y2, gx, gy);
	if (d2 <= thresh*thresh)
	    return d2;
	return -1;
    }

}
