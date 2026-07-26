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

    class WireElm extends CircuitElm {
	int busWidth = 1;
	double[] currents;

	public WireElm(int xx, int yy) { super(xx, yy); }
	public WireElm(int xa, int ya, int xb, int yb, int f,
		       StringTokenizer st) {
	    super(xa, ya, xb, yb, f);
	}
	static final int FLAG_SHOWCURRENT = 1;
	static final int FLAG_SHOWVOLTAGE = 2;
	static final int FLAG_SHOW_BUS_VALUE = 4;
	static final int FLAG_SHOW_BUS_VALUE_HEX = 8;

	int getPostCount() { return busWidth * 2; }
	int getBusWidth() { return busWidth; }

	Point getPost(int n) {
	    if (busWidth == 1)
		return (n == 0) ? point1 : point2;
	    if (n < busWidth)
		return new Point(point1.x, point1.y, n);
	    return new Point(point2.x, point2.y, n - busWidth);
	}

	int getPostWidth(int n) { return busWidth; }

	boolean getConnection(int n1, int n2) {
	    if (busWidth == 1)
		return true;
	    // only connect matching bits: post n1 connects to n1 +/- busWidth
	    return Math.abs(n1 - n2) == busWidth;
	}

	Point getConnectedPost() {
	    return point2;
	}

	Point getConnectedPost(int n) {
	    if (busWidth == 1)
		return (n == 0) ? point2 : point1;
	    if (n < busWidth)
		return new Point(point2.x, point2.y, n);
	    return new Point(point1.x, point1.y, n - busWidth);
	}

	int getBusValue() {
	    int value = 0;
	    for (int i = 0; i < busWidth; i++)
		if (volts[i] > 2.5)
		    value |= 1 << i;
	    return value;
	}

	void draw(Graphics g) {
	    if (currents != null) {
		current = 0;
		for (int i = 0; i < currents.length; i++)
		    current += currents[i];
	    }
	    setVoltageColor(g, volts[0]);
	    drawThickLine(g, point1, point2, (busWidth > 1) ? 5 : 3);
	    doDots(g);
	    setBbox(point1, point2, 3);
	    String s = "";
	    if (busWidth > 1 && (mustShowBusValue() || mustShowBusValueHex())) {
		int value = getBusValue();
		if (mustShowBusValue())
		    s = ""+value;
		if (mustShowBusValueHex())
		    s = (s.length() > 0 ? s + " " : "") + "0x" + Integer.toHexString(value).toUpperCase();
	    } else if (busWidth == 1) {
		if (mustShowCurrent())
		    s = getShortUnitText(Math.abs(getCurrent()), "A");
		if (mustShowVoltage())
		    s = (s.length() > 0 ? s + " " : "") + getShortUnitText(volts[0], "V");
	    }
	    drawValues(g, s, 4);
	    drawPosts(g);
	}
	void stamp() {
//	    sim.stampVoltageSource(nodes[0], nodes[1], voltSource, 0);
	}
	boolean mustShowCurrent() {
	    return (flags & FLAG_SHOWCURRENT) != 0;
	}
	boolean mustShowVoltage() {
	    return (flags & FLAG_SHOWVOLTAGE) != 0;
	}
	boolean mustShowBusValue() {
	    return (flags & FLAG_SHOW_BUS_VALUE) != 0;
	}
	boolean mustShowBusValueHex() {
	    return (flags & FLAG_SHOW_BUS_VALUE_HEX) != 0;
	}
//	int getVoltageSourceCount() { return 1; }
	void getInfo(String arr[]) {
	    arr[0] = (busWidth > 1) ? "bus wire (" + busWidth + ")" : "wire";
	    if (busWidth > 1) {
		int value = getBusValue();
		arr[1] = "value = " + value;
		arr[2] = "hex = 0x" + Integer.toHexString(value).toUpperCase();
		String label = LabeledNodeElm.getLabelForNode(getNode(0));
		if (label != null) {
		    for (int i = 1; i < busWidth; i++) {
			if (!label.equals(LabeledNodeElm.getLabelForNode(getNode(i)))) {
			    label = null;
			    break;
			}
		    }
		}
		if (label != null)
		    arr[3] = label;
	    } else {
		arr[1] = "I = " + getCurrentDText(getCurrent());
		arr[2] = "V = " + getVoltageText(volts[0]);
		String label = LabeledNodeElm.getLabelForNode(getNode(0));
		if (label != null)
		    arr[3] = label;
	    }
	}
	int getDumpType() { return 'w'; }
	double getPower() { return 0; }
	double getVoltageDiff() { return volts[0]; }
	boolean isWireEquivalent() { return true; }
	boolean isRemovableWire() { return true; }

	void setWireCurrent(int bit, double c) {
	    if (currents != null)
		currents[bit] = c;
	    else
		current = c;
	}

	double getCurrentIntoNode(int n) {
	    if (currents != null) {
		if (n < busWidth)
		    return -currents[n];
		return currents[n - busWidth];
	    }
	    if (n == 0)
		return -current;
	    return current;
	}
	public EditInfo getEditInfo(int n) {
	    if (n == 0) {
		EditInfo ei = new EditInfo("", 0, -1, -1);
		ei.checkbox = new Checkbox("Show Current", mustShowCurrent());
		return ei;
	    }
	    if (n == 1) {
		EditInfo ei = new EditInfo("", 0, -1, -1);
		ei.checkbox = new Checkbox("Show Voltage", mustShowVoltage());
		return ei;
	    }
	    if (n == 2) {
		EditInfo ei = new EditInfo("", 0, -1, -1);
		ei.checkbox = new Checkbox("Show Bus Value", mustShowBusValue());
		return ei;
	    }
	    if (n == 3) {
		EditInfo ei = new EditInfo("", 0, -1, -1);
		ei.checkbox = new Checkbox("Show Bus Value (Hex)", mustShowBusValueHex());
		return ei;
	    }
	    return null;
	}
	public void setEditValue(int n, EditInfo ei) {
	    if (n == 0) {
		if (ei.checkbox.getState())
		    flags |= FLAG_SHOWCURRENT;
		else
		    flags &= ~FLAG_SHOWCURRENT;
	    }
	    if (n == 1) {
		if (ei.checkbox.getState())
		    flags |= FLAG_SHOWVOLTAGE;
		else
		    flags &= ~FLAG_SHOWVOLTAGE;
	    }
	    if (n == 2)
		flags = ei.changeFlag(flags, FLAG_SHOW_BUS_VALUE);
	    if (n == 3)
		flags = ei.changeFlag(flags, FLAG_SHOW_BUS_VALUE_HEX);
	}
        int getShortcut() { return 'w'; }

	static boolean pointOnWireInteriorForPoints(int px, int py, ArrayList<Point> pts) {
	    for (int i = 0; i < pts.size() - 1; i++) {
		Point a = pts.get(i);
		Point b = pts.get(i + 1);
		if (pointOnSegmentInterior(a.x, a.y, b.x, b.y, px, py)) return true;
	    }
	    // an interior bend vertex (not the wire's overall endpoints) is also a valid split point,
	    // even though it's not "interior" to either of its adjacent segments
	    for (int i = 1; i < pts.size() - 1; i++) {
		Point p = pts.get(i);
		if (p.x == px && p.y == py) return true;
	    }
	    return false;
	}

	boolean pointOnWireInterior(int px, int py) {
	    ArrayList<Point> pts = new ArrayList<Point>();
	    pts.add(new Point(x, y));
	    pts.add(new Point(x2, y2));
	    return pointOnWireInteriorForPoints(px, py, pts);
	}

	WireElm split(int px, int py) {
	    WireElm newWire = new WireElm(px, py);
	    newWire.drag(x2, y2);
	    drag(px, py);
	    return newWire;
	}

	// True if some other 2-terminal element already connects (ax,ay) directly to
	// (bx,by). Used to avoid laying a redundant parallel wire segment on top of
	// an existing colinear element, which would create an electrical loop.
	boolean hasDirectConnection(int ax, int ay, int bx, int by) {
	    for (CircuitElm ce : UIManager.theUI.elmList) {
		if (ce == this || ce.getPostCount() != 2)
		    continue;
		Point p0 = ce.getPost(0);
		Point p1 = ce.getPost(1);
		if ((p0.x == ax && p0.y == ay && p1.x == bx && p1.y == by) ||
		    (p0.x == bx && p0.y == by && p1.x == ax && p1.y == ay))
		    return true;
	    }
	    return false;
	}

	// After a plain wire is newly drawn, split it at any point where another
	// element's post lies in its interior, so it connects there instead of just
	// crossing over it. (RoutedWireElm overrides this to do nothing, since it
	// routes around such posts instead of through them.) Any sub-segment that
	// would duplicate an existing colinear element (both endpoints of that
	// element lying on the new wire) is dropped instead of added, since adding
	// it would just create a parallel loop.
	void draggingDone() {
	    // postDrawList holds the points (as of the last analysis, i.e. before this wire)
	    // where a dot is drawn: dead ends and real junctions, but not plain pass-through
	    // connections between two elements. Only split at those, so we don't tap into
	    // an already-connected pair that isn't meant to be a distinct node.
	    ArrayList<Point> splitPoints = new ArrayList<Point>();
	    for (Point p : CirSim.theApp.postDrawList) {
		if (pointOnSegmentInterior(x, y, x2, y2, p.x, p.y))
		    splitPoints.add(p);
	    }
	    if (splitPoints.isEmpty())
		return;
	    final int x0 = x, y0 = y;
	    Collections.sort(splitPoints, new Comparator<Point>() {
		public int compare(Point a, Point b) {
		    long da = (long)(a.x-x0)*(a.x-x0) + (long)(a.y-y0)*(a.y-y0);
		    long db = (long)(b.x-x0)*(b.x-x0) + (long)(b.y-y0)*(b.y-y0);
		    return (da < db) ? -1 : ((da > db) ? 1 : 0);
		}
	    });
	    // full ordered list of boundary points: original endpoints plus dedup'd splits
	    ArrayList<Point> pts = new ArrayList<Point>();
	    pts.add(new Point(x, y));
	    for (Point p : splitPoints) {
		Point last = pts.get(pts.size() - 1);
		if (p.x != last.x || p.y != last.y)
		    pts.add(p);
	    }
	    Point last = pts.get(pts.size() - 1);
	    if (last.x != x2 || last.y != y2)
		pts.add(new Point(x2, y2));

	    boolean first = true;
	    for (int i = 0; i < pts.size() - 1; i++) {
		Point a = pts.get(i);
		Point b = pts.get(i + 1);
		if (hasDirectConnection(a.x, a.y, b.x, b.y))
		    continue;
		if (first) {
		    x = a.x; y = a.y;
		    drag(b.x, b.y);
		    first = false;
		} else {
		    WireElm seg = new WireElm(a.x, a.y);
		    seg.drag(b.x, b.y);
		    UIManager.theUI.elmList.addElement(seg);
		}
	    }
	    if (first)
		UIManager.theUI.elmList.remove(this);
	}

	int getMouseDistance(int gx, int gy) {
	    int thresh = 10;
	    int d2 = lineDistanceSq(x, y, x2, y2, gx, gy);
	    if (d2 <= thresh*thresh)
		return d2;
	    return -1;
	}

    }
