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

import com.google.gwt.user.client.ui.HasHorizontalAlignment;
import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.event.dom.client.MouseDownEvent;
import com.google.gwt.event.dom.client.MouseDownHandler;
import com.google.gwt.user.client.ui.PopupPanel;
import com.google.gwt.user.client.ui.VerticalPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.event.dom.client.MouseOutEvent;
import com.google.gwt.event.dom.client.MouseOutHandler;
import com.google.gwt.event.dom.client.MouseWheelEvent;
import com.google.gwt.event.dom.client.MouseWheelHandler;

import com.lushprojects.circuitjs1.client.util.Locale;

// Popup for toggling MOSFET/Transistor type on scroll wheel,
// similar to ScrollValuePopup for R/C/L values.
// Also cycles logic gates through AND/NAND/OR/NOR/XOR/XNOR. Gate type is the
// element's class, not a field, so each step replaces the element with a new
// instance of the next class. Invert Inputs and DeMorgan are cleared on each
// step (Invert Inputs changes the gate's function). A scope on the gate is
// removed, same as deleting the gate.
public class TypeScrollPopup extends PopupPanel implements MouseOutHandler, MouseWheelHandler,
	MouseDownHandler {

	CircuitElm myElm;
	CircuitElm originalElm;
	CirSim sim;
	int originalPnp;
	int currentPnp;
	int gateIndex;
	Label typeLabel;
	VerticalPanel vp;
	double deltaY;

	TypeScrollPopup(final int x, final int y, int dy, CircuitElm e, CirSim s) {
		super();
		myElm = e;
		originalElm = e;
		sim = s;
		deltaY = 0;
		sim.undoManager.pushUndo();

		if (e instanceof MosfetElm)
			originalPnp = ((MosfetElm) e).pnp;
		else if (e instanceof TransistorElm)
			originalPnp = ((TransistorElm) e).pnp;
		currentPnp = originalPnp;
		if (e instanceof GateElm)
			gateIndex = gateIndexOf(e);

		vp = new VerticalPanel();
		setWidget(vp);
		Label header = new Label(getHeaderText());
		header.addStyleDependentName("2off");
		vp.add(header);
		typeLabel = new Label();
		typeLabel.setHorizontalAlignment(HasHorizontalAlignment.ALIGN_CENTER);
		typeLabel.addStyleDependentName("selected");
		vp.add(typeLabel);
		updateLabel();

		doDeltaY(dy);

		this.addDomHandler(this, MouseOutEvent.getType());
		this.addDomHandler(this, MouseWheelEvent.getType());
		this.addDomHandler(this, MouseDownEvent.getType());
		setPopupPositionAndShow(new PopupPanel.PositionCallback() {
			public void setPosition(int offsetWidth, int offsetHeight) {
				int left = Math.max(0, (x - offsetWidth / 4));
				int top = Math.max(0, y - offsetHeight / 2);
				setPopupPosition(left, top);
			}
		});
	}

	String getHeaderText() {
		if (myElm instanceof MosfetElm) {
			if (myElm instanceof JfetElm)
				return Locale.LS("JFET Type");
			return Locale.LS("MOSFET Type");
		}
		if (myElm instanceof GateElm)
			return Locale.LS("Gate Type");
		return Locale.LS("Transistor Type");
	}

	public void doDeltaY(int dy) {
		deltaY += dy;
		// toggle on every 30px of scroll (similar to ScrollValuePopup sensitivity)
		while (deltaY >= 30) {
			deltaY -= 30;
			toggle(1);
		}
		while (deltaY <= -30) {
			deltaY += 30;
			toggle(-1);
		}
	}

	// gate cycle order: AND, NAND, OR, NOR, XOR, XNOR (wraps around).
	// subclasses checked before their parents (Nand extends And, etc.)
	int gateIndexOf(CircuitElm e) {
		if (e instanceof NandGateElm) return 1;
		if (e instanceof AndGateElm)  return 0;
		if (e instanceof XnorGateElm) return 5;
		if (e instanceof XorGateElm)  return 4;
		if (e instanceof NorGateElm)  return 3;
		return 2; // OrGateElm
	}

	GateElm newGate(int i, int x, int y) {
		switch (i) {
		case 0:  return new AndGateElm(x, y);
		case 1:  return new NandGateElm(x, y);
		case 2:  return new OrGateElm(x, y);
		case 3:  return new NorGateElm(x, y);
		case 4:  return new XorGateElm(x, y);
		default: return new XnorGateElm(x, y);
		}
	}

	void stepGate(int dir) {
		GateElm old = (GateElm) myElm;
		gateIndex = (gateIndex + dir + 6) % 6;
		GateElm g = newGate(gateIndex, old.x, old.y);
		g.x2 = old.x2;
		g.y2 = old.y2;
		g.flags = old.flags & ~(g.FLAG_INVERT_INPUTS | g.FLAG_DEMORGAN);
		g.inputCount = old.inputCount;
		g.highVoltage = old.highVoltage;
		g.propagationDelay = old.propagationDelay;
		g.setSize((g.flags & g.FLAG_SMALL) != 0 ? 1 : 2);
		g.allocNodes();
		g.setupVolts();
		g.setPoints();
		replaceElm(g);
	}

	// put newElm in myElm's place in the circuit
	void replaceElm(CircuitElm newElm) {
		int i = sim.locateElm(myElm);
		sim.elmList.set(i, newElm);
		newElm.setSelected(myElm.isSelected());
		sim.mouse.setMouseElm(newElm);
		myElm = newElm;
		sim.scopeManager.deleteUnusedScopeElms();
	}

	void toggle(int dir) {
		if (myElm instanceof GateElm) {
			stepGate(dir);
		} else if (myElm instanceof MosfetElm) {
			MosfetElm m = (MosfetElm) myElm;
			m.pnp = -m.pnp;
			m.flags ^= m.FLAG_PNP;
			m.setPoints();
			currentPnp = m.pnp;
		} else if (myElm instanceof TransistorElm) {
			TransistorElm t = (TransistorElm) myElm;
			t.pnp = -t.pnp;
			t.setPoints();
			currentPnp = t.pnp;
		}
		sim.needAnalyze();
		updateLabel();
	}

	void updateLabel() {
		String text;
		if (myElm instanceof GateElm) {
			text = Locale.LS(((GateElm) myElm).getGateName());
		} else if (myElm instanceof MosfetElm) {
			if (myElm instanceof JfetElm)
				text = (currentPnp == -1) ? Locale.LS("P-Channel JFET") : Locale.LS("N-Channel JFET");
			else
				text = (currentPnp == -1) ? Locale.LS("P-Channel") : Locale.LS("N-Channel");
		} else {
			text = (currentPnp == -1) ? "PNP" : "NPN";
		}
		typeLabel.setText(text);
	}

	public void close(boolean keepChanges) {
		if (!keepChanges) {
			// revert to original state
			if (myElm != originalElm) {
				// gate: put the original element back, flags and all
				replaceElm(originalElm);
				sim.needAnalyze();
			}
			while (currentPnp != originalPnp)
				toggle(1);
		}
		this.hide();
		CirSim.typeScrollPopup = null;
	}

	public void onMouseOut(MouseOutEvent e) {
		close(true);
	}

	public void onMouseWheel(MouseWheelEvent e) {
		e.preventDefault();
		doDeltaY(e.getDeltaY());
	}

	public void onMouseDown(MouseDownEvent e) {
		if (e.getNativeButton() == NativeEvent.BUTTON_LEFT || e.getNativeButton() == NativeEvent.BUTTON_MIDDLE)
			close(true);
		else
			close(false);
	}
}
