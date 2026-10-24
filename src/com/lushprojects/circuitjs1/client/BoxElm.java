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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Vector;

import com.google.gwt.canvas.dom.client.Context2d;
import com.google.gwt.xml.client.Document;
import com.google.gwt.xml.client.Element;

class BoxElm extends ShapeElm {

    int opacity;	// fill, 0-100%; 0 = no fill
    int cornerRadius;

    // box-only memory of the last box drawn or edited
    static int lastOpacity;
    static int lastCornerRadius;

    public BoxElm(int xx, int yy) {
	super(xx, yy);
	x2 = xx;
	y2 = yy;
	opacity = lastOpacity;
	cornerRadius = lastCornerRadius;
	setBbox(x, y, x2, y2);
    }

    public BoxElm(int xa, int ya, int xb, int yb, int f,
		   StringTokenizer st) {
	super(xa, ya, xb, yb, f);
	x2 = xb;
	y2 = yb;
	setBbox(x, y, x2, y2);
    }

    int getDumpType() { return 'b'; }
    int defaultStyle() { return STYLE_DASHED; }

    void remember() {
	super.remember();
	lastOpacity = opacity;
	lastCornerRadius = cornerRadius;
    }

    boolean creationFailed() {
	return Math.abs(x2-x) < 32 || Math.abs(y2-y) < 32;
    }

    // Shift: square, using the longer side
    void constrain() {
	int dx = x2-x, dy = y2-y;
	int d = Math.max(Math.abs(dx), Math.abs(dy));
	x2 = x + (dx < 0 ? -d : d);
	y2 = y + (dy < 0 ? -d : d);
    }
    
    void draw(Graphics g) {
	setBbox(x, y, x2, y2);
	int bx = Math.min(x, x2), by = Math.min(y, y2);
	int bw = Math.abs(x2-x), bh = Math.abs(y2-y);
	int r = Math.min(cornerRadius, Math.min(bw, bh)/2);
	Context2d ctx = g.context;
	if (opacity > 0) {
	    g.setColor(shapeColor());
	    ctx.save();
	    ctx.setGlobalAlpha(opacity/100.);
	    boxPath(ctx, bx, by, bw, bh, r);
	    ctx.fill();
	    ctx.restore();
	}
	beginStroke(g);
	if (r == 0)
	    g.drawRect(bx, by, bw, bh);
	else {
	    boxPath(ctx, bx, by, bw, bh, r);
	    ctx.stroke();
	}
	endStroke(g);
    }

    // rectangle outline with rounded corners (arcs, so it survives SVG export)
    static void boxPath(Context2d ctx, int bx, int by, int bw, int bh, int r) {
	ctx.beginPath();
	if (r == 0) {
	    ctx.rect(bx, by, bw, bh);
	    return;
	}
	ctx.moveTo(bx+r, by);
	ctx.lineTo(bx+bw-r, by);
	ctx.arc(bx+bw-r, by+r, r, -Math.PI/2, 0);
	ctx.lineTo(bx+bw, by+bh-r);
	ctx.arc(bx+bw-r, by+bh-r, r, 0, Math.PI/2);
	ctx.lineTo(bx+r, by+bh);
	ctx.arc(bx+r, by+bh-r, r, Math.PI/2, Math.PI);
	ctx.lineTo(bx, by+r);
	ctx.arc(bx+r, by+r, r, Math.PI, Math.PI*1.5);
	ctx.closePath();
    }

    // Put boxes first in the list, so they're drawn (and saved) behind everything else,
    // with larger boxes ahead of the smaller ones they hold.  Everything else keeps its
    // order.  Boxes have no posts, so this doesn't change the simulation; hints refer to
    // elements by position, so they're remapped to follow their elements.
    static void sortBoxes(CirSim app, Vector<CircuitElm> list) {
	CircuitElm h1 = null, h2 = null;
	boolean hint = (list == app.elmList && app.hintType != -1);
	if (hint) {
	    h1 = app.getElm(app.hintItem1);
	    h2 = app.getElm(app.hintItem2);
	}
	ArrayList<BoxElm> boxes = new ArrayList<BoxElm>();
	ArrayList<CircuitElm> rest = new ArrayList<CircuitElm>();
	for (CircuitElm ce : list) {
	    if (ce instanceof BoxElm)
		boxes.add((BoxElm) ce);
	    else
		rest.add(ce);
	}
	if (boxes.isEmpty())
	    return;
	Collections.sort(boxes, new Comparator<BoxElm>() {
	    public int compare(BoxElm a, BoxElm b) {
		double d = b.area() - a.area();
		return d > 0 ? 1 : (d < 0 ? -1 : 0);
	    }
	});
	list.clear();
	list.addAll(boxes);
	list.addAll(rest);
	if (hint) {
	    app.hintItem1 = list.indexOf(h1);
	    app.hintItem2 = list.indexOf(h2);
	}
    }

    double area() { return (double) Math.abs(x2-x) * Math.abs(y2-y); }

    int extraCount() { return 2; }

    EditInfo getExtraEditInfo(int n) {
	if (n == 0)
	    return new EditInfo("Fill Opacity (%)", opacity, 0, 100).setDimensionless().setUnitStep().disallowSliders();
	if (n == 1)
	    return new EditInfo("Corner Radius", cornerRadius, 0, 100).setDimensionless().setUnitStep().disallowSliders();
	return null;
    }

    void setExtraEditValue(int n, EditInfo ei) {
	int v = (int) Math.round(ei.value);
	if (n == 0) {
	    if (v < 0 || v > 100) {
		ei.setError("must be from 0 to 100");
		return;
	    }
	    opacity = v;
	}
	if (n == 1) {
	    if (v < 0) {
		ei.setError("must be 0 or more");
		return;
	    }
	    cornerRadius = v;
	}
    }

    void dumpXml(Document doc, Element elem) {
	super.dumpXml(doc, elem);
	if (opacity != 0)
	    XMLSerializer.dumpAttr(elem, "op", opacity);
	if (cornerRadius != 0)
	    XMLSerializer.dumpAttr(elem, "cr", cornerRadius);
    }

    void undumpXml(XMLDeserializer xml) {
	super.undumpXml(xml);
	opacity = xml.parseIntAttr("op", 0);
	cornerRadius = xml.parseIntAttr("cr", 0);
    }

    // only the edges count, so whatever is inside the box can still be picked
    int getMouseDistance(int gx, int gy) {
	int thresh = Math.max(10, thickness/2+4);
	int dx1 = Math.abs(gx-x);
	int dy1 = Math.abs(gy-y);
	int dx2 = Math.abs(gx-x2);
	int dy2 = Math.abs(gy-y2);
	if (Math.abs(dx1) < thresh)
	   return dx1*dx1;
	if (Math.abs(dx2) < thresh)
	   return dx2*dx2;
	if (Math.abs(dy1) < thresh)
	   return dy1*dy1;
	if (Math.abs(dy2) < thresh)
	   return dy2*dy2;
	return -1;
    }

    void selectRect(Rectangle r, boolean add) {
        if (r.contains(boundingBox))
            selected = true;
        else if (!add)
            selected = false;
    }
}
