/*
    Copyright (C) Paul Falstad

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

// PID controller.
// Kp, Ki, Kd pins: manual mode = gain inputs, 1V = gain of 1 (Ki per second, Kd in seconds).
//                  auto mode = on/off switches (exactly 0V = term off).
// Kp->, Ki->, Kd-> pins: the gains actually in use.
// In pin: error signal.  Out pin: correction = Kp*e + Ki*integral(e) + Kd*de/dt.
// The controller is sampled: inputs are read once per completed timestep and the
// output is held for the next step (one-timestep lag, like a digital controller).
// Out is clamped to Out Min..Out Max.  Anti-windup (conditional integration) stops the
// integrator while Out is clamped and the error would push it further into the limit.
// D filter: first-order low-pass on the D term.  Auto D Filter (default): tau = Td/N with
// Td = Kd/Kp and N = 10, as real controllers do, so the kick doesn't depend on the timestep.
// Otherwise (or with Kp = 0) tau = dTau (0 = raw derivative).
//
// Output: Analog (Out = correction) or PWM (Out = High/Low from comparing the correction
// with an internal 0..Ramp Peak sawtooth at the PWM frequency; duty = correction/Ramp Peak,
// limited to Min..Max Duty, which become the correction limits for clamp and anti-windup).
//
// Auto mode: relay autotune.  After reset the output is a relay (bias +/- h, switching on
// the sign of the error).  The loop oscillates; the period Tu and error amplitude a are
// measured once the oscillation has stopped growing, giving Ku = 4h/(pi*a).  Gains then come from Ziegler-Nichols using the terms
// switched on, recomputed every step so switching a term on/off retunes instantly.
class PIDElm extends ChipElm {
    static final int P_KP = 0;
    static final int P_KI = 1;
    static final int P_KD = 2;
    static final int P_IN = 3;
    static final int P_OUT = 4;
    static final int P_KPO = 5;	// gain outputs: the gains actually in use
    static final int P_KIO = 6;
    static final int P_KDO = 7;

    double integral, lastErr, lastT;
    boolean started;
    double pTerm, iTerm, dTerm, output;
    double kpUsed, kiUsed, kdUsed;
    boolean limited;

    // settings
    double outMax = 15, outMin = -15;
    double dTau = 0;
    boolean antiWindup = true;
    boolean autoMode = false;
    double tuneH = 1, tuneBias = 0;
    double tuneHyst = 0;	// relay hysteresis: ignore error crossings smaller than this (e.g. PWM ripple)
    boolean autoDFilter = true;
    static final double D_FILTER_N = 10;
    boolean excludeDScale = false;	// scope auto-scale ignores the D trace (it's still drawn, clipped)
    static final int OUT_ANALOG = 0;
    static final int OUT_PWM = 1;
    int outputMode = OUT_ANALOG;
    double pwmFreq = 20000, rampPeak = 1, dutyMin = 0, dutyMax = .95, pwmHigh = 5, pwmLow = 0;
    double ramp;
    boolean pwmOn;

    // autotune state
    static final double STEADY_TOL = .03;	// oscillation is steady when successive amplitudes agree this closely
    static final int MAX_SETTLE_CYCLES = 20;	// measure anyway after this many cycles
    static final int MEASURE_CYCLES = 3;	// cycles averaged
    boolean tuned, relayHigh, relayStarted, measuring;
    int cycles, measured;
    double lastRiseT, eMax, eMin, sumPeriod, sumAmp, lastAmp;
    double ku, tu;
    // auto bias: if the error settles without crossing zero, the setpoint is out of
    // reach of bias +/- h, so the bias steps by h toward it.  "Settled" = the error's
    // rate of change has stayed below 1% of its peak (since the last switch or bias
    // step) for at least as long as it took to get there.  Scale-free, no time constants.
    double bias, peakRate, switchT, lowSince;
    boolean biasMoved;

    public PIDElm(int xx, int yy) { super(xx, yy); }
    public PIDElm(int xa, int ya, int xb, int yb, int f,
		  StringTokenizer st) {
	super(xa, ya, xb, yb, f, st);
	if (st.hasMoreTokens()) {
	    outMax = Double.parseDouble(st.nextToken());
	    outMin = Double.parseDouble(st.nextToken());
	    dTau = Double.parseDouble(st.nextToken());
	    antiWindup = Integer.parseInt(st.nextToken()) != 0;
	    autoMode = Integer.parseInt(st.nextToken()) != 0;
	    tuneH = Double.parseDouble(st.nextToken());
	    tuneBias = Double.parseDouble(st.nextToken());
	}
	if (st.hasMoreTokens())
	    excludeDScale = Integer.parseInt(st.nextToken()) != 0;
	if (st.hasMoreTokens())
	    autoDFilter = Integer.parseInt(st.nextToken()) != 0;
	if (st.hasMoreTokens()) {
	    outputMode = Integer.parseInt(st.nextToken());
	    pwmFreq = Double.parseDouble(st.nextToken());
	    rampPeak = Double.parseDouble(st.nextToken());
	    dutyMin = Double.parseDouble(st.nextToken());
	    dutyMax = Double.parseDouble(st.nextToken());
	    pwmHigh = Double.parseDouble(st.nextToken());
	    pwmLow = Double.parseDouble(st.nextToken());
	}
	if (st.hasMoreTokens())
	    tuneHyst = Double.parseDouble(st.nextToken());
    }

    String dump() {
	return super.dump() + " " + outMax + " " + outMin + " " + dTau + " " + (antiWindup ? 1 : 0) +
	    " " + (autoMode ? 1 : 0) + " " + tuneH + " " + tuneBias + " " + (excludeDScale ? 1 : 0) +
	    " " + (autoDFilter ? 1 : 0) +
	    " " + outputMode + " " + pwmFreq + " " + rampPeak + " " + dutyMin + " " + dutyMax +
	    " " + pwmHigh + " " + pwmLow + " " + tuneHyst;
    }

    void dumpXml(Document doc, Element elem) {
	super.dumpXml(doc, elem);
	XMLSerializer.dumpAttr(elem, "omx", outMax);
	XMLSerializer.dumpAttr(elem, "omn", outMin);
	XMLSerializer.dumpAttr(elem, "dtau", dTau);
	XMLSerializer.dumpAttr(elem, "aw", antiWindup);
	XMLSerializer.dumpAttr(elem, "auto", autoMode);
	XMLSerializer.dumpAttr(elem, "th", tuneH);
	XMLSerializer.dumpAttr(elem, "tb", tuneBias);
	XMLSerializer.dumpAttr(elem, "xd", excludeDScale);
	XMLSerializer.dumpAttr(elem, "adf", autoDFilter);
	XMLSerializer.dumpAttr(elem, "om", outputMode);
	XMLSerializer.dumpAttr(elem, "pf", pwmFreq);
	XMLSerializer.dumpAttr(elem, "rp", rampPeak);
	XMLSerializer.dumpAttr(elem, "dmn", dutyMin);
	XMLSerializer.dumpAttr(elem, "dmx", dutyMax);
	XMLSerializer.dumpAttr(elem, "ph", pwmHigh);
	XMLSerializer.dumpAttr(elem, "pl", pwmLow);
	XMLSerializer.dumpAttr(elem, "thy", tuneHyst);
    }

    void undumpXml(XMLDeserializer xml) {
	super.undumpXml(xml);
	outMax = xml.parseDoubleAttr("omx", outMax);
	outMin = xml.parseDoubleAttr("omn", outMin);
	dTau = xml.parseDoubleAttr("dtau", dTau);
	antiWindup = xml.parseBooleanAttr("aw", antiWindup);
	autoMode = xml.parseBooleanAttr("auto", autoMode);
	tuneH = xml.parseDoubleAttr("th", tuneH);
	tuneBias = xml.parseDoubleAttr("tb", tuneBias);
	excludeDScale = xml.parseBooleanAttr("xd", excludeDScale);
	autoDFilter = xml.parseBooleanAttr("adf", autoDFilter);
	outputMode = xml.parseIntAttr("om", outputMode);
	pwmFreq = xml.parseDoubleAttr("pf", pwmFreq);
	rampPeak = xml.parseDoubleAttr("rp", rampPeak);
	dutyMin = xml.parseDoubleAttr("dmn", dutyMin);
	dutyMax = xml.parseDoubleAttr("dmx", dutyMax);
	pwmHigh = xml.parseDoubleAttr("ph", pwmHigh);
	pwmLow = xml.parseDoubleAttr("pl", pwmLow);
	tuneHyst = xml.parseDoubleAttr("thy", tuneHyst);
    }

    String getChipName() { return "PID Controller"; }
    @Override boolean isDigitalChip() { return false; }

    void setupPins() {
	sizeX = 4;
	sizeY = 5;
	pins = new Pin[8];
	pins[P_KP] = new Pin(0, SIDE_W, "Kp");
	pins[P_KI] = new Pin(1, SIDE_W, "Ki");
	pins[P_KD] = new Pin(2, SIDE_W, "Kd");
	pins[P_IN] = new Pin(4, SIDE_W, "In");
	pins[P_OUT] = new Pin(4, SIDE_E, "Out");
	pins[P_OUT].output = true;
	pins[P_KPO] = new Pin(0, SIDE_E, "Kp\u2192");
	pins[P_KIO] = new Pin(1, SIDE_E, "Ki\u2192");
	pins[P_KDO] = new Pin(2, SIDE_E, "Kd\u2192");
	pins[P_KPO].output = pins[P_KIO].output = pins[P_KDO].output = true;
	allocNodes();
    }

    // scope: plain voltage/current mean the Out pin; P/I/D and error are extra values
    static final int SV_ERR = Scope.VAL_EXTRA;
    static final int SV_P   = Scope.VAL_EXTRA+1;
    static final int SV_I   = Scope.VAL_EXTRA+2;
    static final int SV_D   = Scope.VAL_EXTRA+3;
    static final int SV_CORR = Scope.VAL_EXTRA+4;	// PWM mode: the correction, compared with...
    static final int SV_RAMP = Scope.VAL_EXTRA+5;	// ...the ramp

    boolean canViewInScope() { return true; }
    double getVoltageDiff() { return volts[P_OUT]; }
    double getCurrent() { return pins[P_OUT].current; }

    int[] getExtraScopeValues() {
	if (outputMode == OUT_PWM)
	    return new int[] { SV_ERR, SV_P, SV_I, SV_D, SV_CORR, SV_RAMP };
	return new int[] { SV_ERR, SV_P, SV_I, SV_D };
    }
    String getExtraScopeValueName(int v) {
	switch (v) {
	case SV_ERR: return "Error";
	case SV_P: return "P";
	case SV_I: return "I";
	case SV_D: return "D";
	case SV_CORR: return "Correction";
	case SV_RAMP: return "Ramp";
	}
	return null;
    }
    double getScopeValue(int v) {
	switch (v) {
	case SV_ERR: return volts[P_IN];
	case SV_P: return pTerm;
	case SV_I: return iTerm;
	case SV_D: return dTerm;
	case SV_CORR: return output;
	case SV_RAMP: return ramp;
	}
	return super.getScopeValue(v);
    }
    boolean excludeScopeValueFromScale(int v) { return excludeDScale && v == SV_D; }
    String getScopeText(int v) {
	String n = getExtraScopeValueName(v);
	if (n == null)
	    n = (v == Scope.VAL_CURRENT) ? "Out I" : "Out";
	return "PID, " + n;
    }

    int getPostCount() { return 8; }
    int getVoltageSourceCount() { return 4; }
    int getDumpType() { return 442; }

    boolean isTuning() { return autoMode && !tuned; }

    // name shown on the chip: which terms are active (gain pin exactly 0 = off)
    String getModeName() {
	if (isTuning())
	    return "TUNE";
	String s = "";
	if (volts[P_KP] != 0) s += "P";
	if (volts[P_KI] != 0) s += "I";
	if (volts[P_KD] != 0) s += "D";
	return s.length() == 0 ? "OFF" : s;
    }

    // draw the name on row 3, the empty row between Kd and In
    void drawLabel(Graphics g, int x, int y) {
	int row = isFlippedY() ? sizeY-1-3 : 3;
	int ly = rectPointsY[0] + cspc + row*cspc2;
	if (isFlippedXY())
	    ly = y;   // rotated: just use the center
	g.save();
	g.setFont(new Font("normal", 0, 10*csize));
	g.context.setTextBaseline("middle");
	g.context.setTextAlign("center");
	g.drawString(getModeName() + (outputMode == OUT_PWM ? " \u2192 PWM" : ""), x, ly);
	g.restore();
    }

    // correction limits: Out Min/Max, or in PWM mode the duty limits in ramp volts
    double outHi() { return outputMode == OUT_PWM ? dutyMax*rampPeak : outMax; }
    double outLo() { return outputMode == OUT_PWM ? dutyMin*rampPeak : outMin; }

    // PWM: compare the correction with the sawtooth ramp
    void updatePwm() {
	double ph = sim.t*pwmFreq;
	ramp = rampPeak*(ph - Math.floor(ph));
	pwmOn = output > ramp;
    }

    // hold the outputs for this step
    void doStep() {
	double vOut = (outputMode == OUT_PWM) ? (pwmOn ? pwmHigh : pwmLow) : output;
	sim.updateVoltageSource(CircuitNode.ground, nodes[P_OUT], pins[P_OUT].voltSource, vOut);
	sim.updateVoltageSource(CircuitNode.ground, nodes[P_KPO], pins[P_KPO].voltSource, kpUsed);
	sim.updateVoltageSource(CircuitNode.ground, nodes[P_KIO], pins[P_KIO].voltSource, kiUsed);
	sim.updateVoltageSource(CircuitNode.ground, nodes[P_KDO], pins[P_KDO].voltSource, kdUsed);
    }

    // auto mode: Ziegler-Nichols gains from Ku, Tu for the terms switched on.
    // Needs P; without it all gains are 0.
    void autoGains() {
	boolean p = volts[P_KP] != 0, i = volts[P_KI] != 0, d = volts[P_KD] != 0;
	kpUsed = kiUsed = kdUsed = 0;
	if (!p)
	    return;
	if (i && d) {
	    kpUsed = .6*ku;  kiUsed = kpUsed/(tu/2);  kdUsed = kpUsed*tu/8;
	} else if (i) {
	    kpUsed = .45*ku; kiUsed = kpUsed/(tu/1.2);
	} else if (d) {
	    kpUsed = .8*ku;  kdUsed = kpUsed*tu/8;
	} else
	    kpUsed = .5*ku;
    }

    // relay autotune, one completed timestep
    void tuneStep(double err, double dt) {
	if (!relayStarted)
	    relayHigh = err > 0;
	// relay with hysteresis: once high, stay high until the error drops below -hyst,
	// once low, stay low until it rises above +hyst
	boolean high = relayHigh ? (err > -tuneHyst) : (err > tuneHyst);
	if (!relayStarted) {
	    relayStarted = true;
	    relayHigh = high;
	    eMax = eMin = err;
	    bias = tuneBias;
	    switchT = sim.t;
	    peakRate = 0;
	    lowSince = -1;
	}

	// auto bias: watch for the error settling on one side
	if (dt > 0 && high == relayHigh) {
	    double rate = Math.abs(err-lastErr)/dt;
	    peakRate = Math.max(peakRate, rate);
	    if (peakRate > 0 && rate < .01*peakRate) {
		if (lowSince < 0)
		    lowSince = sim.t;
		else if (sim.t - lowSince >= lowSince - switchT) {
		    // settled without crossing: step the bias toward the setpoint
		    bias += relayHigh ? tuneH : -tuneH;
		    biasMoved = true;
		    switchT = sim.t;
		    peakRate = 0;
		    lowSince = -1;
		    // start the oscillation measurement over
		    measuring = false;
		    measured = 0;
		    lastRiseT = lastAmp = sumPeriod = sumAmp = 0;
		}
	    } else
		lowSince = -1;
	}
	eMax = Math.max(eMax, err);
	eMin = Math.min(eMin, err);
	if (high != relayHigh) {
	    relayHigh = high;
	    switchT = sim.t;
	    peakRate = 0;
	    lowSince = -1;
	    // a full cycle runs from one switch-to-high to the next
	    if (high) {
		if (lastRiseT > 0) {
		    cycles++;
		    double amp = (eMax-eMin)/2;
		    if (measuring) {
			sumPeriod += sim.t - lastRiseT;
			sumAmp += amp;
			measured++;
		    } else if ((lastAmp > 0 && Math.abs(amp-lastAmp) <= STEADY_TOL*amp) ||
			       cycles >= MAX_SETTLE_CYCLES)
			measuring = true;	// steady: measure the following cycles
		    lastAmp = amp;
		    if (measured == MEASURE_CYCLES) {
			tu = sumPeriod/MEASURE_CYCLES;
			double a = sumAmp/MEASURE_CYCLES;
			// relay amplitude actually applied (bias +/- h may be clipped by the limits)
			double hEff = (Math.min(outHi(), bias+tuneH) - Math.max(outLo(), bias-tuneH))/2;
			// relay with hysteresis: Ku = 4h / (pi*sqrt(a^2 - hyst^2))
			double ae = (a > tuneHyst) ? Math.sqrt(a*a - tuneHyst*tuneHyst) : a;
			ku = 4*hEff/(Math.PI*ae);
			tuned = true;
			autoGains();
			// bumpless: start the integrator so Out begins at the bias level
			integral = (kiUsed != 0) ? bias/kiUsed : 0;
		    }
		}
		lastRiseT = sim.t;
		eMax = eMin = err;
	    }
	}
	pTerm = iTerm = dTerm = 0;
	output = Math.max(outLo(), Math.min(outHi(), bias + (relayHigh ? tuneH : -tuneH)));
	limited = false;
    }

    // sample inputs once per completed timestep and compute next output
    void stepFinished() {
	double err = volts[P_IN];
	double dt = sim.t - lastT;
	if (!started) {
	    lastErr = err;
	    started = true;
	    dt = 0;
	}
	if (isTuning()) {
	    tuneStep(err, dt);
	    updatePwm();
	    lastErr = err;
	    lastT = sim.t;
	    return;
	}
	if (autoMode)
	    autoGains();
	else {
	    kpUsed = volts[P_KP]; kiUsed = volts[P_KI]; kdUsed = volts[P_KD];
	}
	double kp = kpUsed, ki = kiUsed, kd = kdUsed;
	pTerm = kp*err;

	// derivative, optionally low-pass filtered
	if (dt > 0) {
	    double dRaw = kd*(err-lastErr)/dt;
	    double tau = (autoDFilter && kp != 0) ? Math.abs(kd/kp)/D_FILTER_N : dTau;
	    if (tau > 0)
		dTerm += (dRaw-dTerm)*dt/(tau+dt);
	    else
		dTerm = dRaw;
	} else
	    dTerm = 0;

	// integral, with anti-windup: skip integrating if that would push
	// a clamped output further past its limit
	double newIntegral = integral + err*dt;
	double u = pTerm + ki*newIntegral + dTerm;
	boolean pushingHigh = u > outHi() && ki*err > 0;
	boolean pushingLow  = u < outLo() && ki*err < 0;
	if (!(antiWindup && (pushingHigh || pushingLow)))
	    integral = newIntegral;
	iTerm = ki*integral;

	// clamp output
	u = pTerm + iTerm + dTerm;
	output = Math.max(outLo(), Math.min(outHi(), u));
	limited = (output != u);
	updatePwm();
	lastErr = err;
	lastT = sim.t;
    }

    void resetTune() {
	tuned = relayHigh = relayStarted = measuring = biasMoved = false;
	bias = tuneBias;
	cycles = measured = 0;
	lastRiseT = eMax = eMin = sumPeriod = sumAmp = lastAmp = 0;
	ku = tu = 0;
    }

    void reset() {
	super.reset();
	integral = lastErr = lastT = 0;
	pTerm = iTerm = dTerm = output = ramp = 0;
	pwmOn = false;
	kpUsed = kiUsed = kdUsed = 0;
	limited = false;
	started = false;
	resetTune();
    }

    void getInfo(String arr[]) {
	arr[0] = "PID " + (autoMode ? "Auto" : "Manual") + " (" + getModeName() + ")";
	arr[1] = "e = " + getVoltageText(volts[P_IN]);
	arr[2] = "P = " + getVoltageText(pTerm);
	arr[3] = "I = " + getVoltageText(iTerm);
	arr[4] = "D = " + getVoltageText(dTerm);
	if (outputMode == OUT_PWM)
	    arr[5] = "Duty = " + Math.round(100*output/rampPeak) + "%" + (limited ? " (limited)" : "");
	else
	    arr[5] = "Out = " + getVoltageText(volts[P_OUT]) + (limited ? " (limited)" : "");
	arr[6] = "Kp = " + getShortUnitText(kpUsed, "");
	arr[7] = "Ki = " + getShortUnitText(kiUsed, "/s");
	arr[8] = "Kd = " + getShortUnitText(kdUsed, "s");
	if (autoMode) {
	    if (isTuning())
		arr[9] = (cycles == 0) ? "Tuning: waiting for oscillation" :
		    !measuring ? "Tuning: waiting for steady oscillation" :
		    "Tuning: measuring cycle " + (measured+1) + " of " + MEASURE_CYCLES;
	    else if (volts[P_KP] == 0)
		arr[9] = "Auto needs P";
	    else
		arr[9] = "Ku = " + getShortUnitText(ku, "") + ", Tu = " + getShortUnitText(tu, "s");
	    if (biasMoved && arr[9] != null)
		arr[9] += ", bias " + getVoltageText(bias);
	}
    }

    // edit dialog items, in order; which ones show depends on Mode and Output
    static final int E_MODE = 0, E_OUTPUT = 1, E_OMAX = 2, E_OMIN = 3, E_FREQ = 4, E_RAMP = 5,
	E_DMIN = 6, E_DMAX = 7, E_HIGH = 8, E_LOW = 9, E_AW = 10, E_ADF = 11, E_DTAU = 12,
	E_XD = 13, E_TH = 14, E_TB = 15, E_THY = 16;

    int[] editItems() {
	int list[] = new int[17];
	int n = 0;
	list[n++] = E_MODE;
	list[n++] = E_OUTPUT;
	if (outputMode == OUT_PWM) {
	    list[n++] = E_FREQ; list[n++] = E_RAMP; list[n++] = E_DMIN; list[n++] = E_DMAX;
	    list[n++] = E_HIGH; list[n++] = E_LOW;
	} else {
	    list[n++] = E_OMAX; list[n++] = E_OMIN;
	}
	list[n++] = E_AW; list[n++] = E_ADF; list[n++] = E_DTAU; list[n++] = E_XD;
	if (autoMode) {
	    list[n++] = E_TH; list[n++] = E_TB; list[n++] = E_THY;
	}
	int r[] = new int[n];
	for (int i = 0; i != n; i++)
	    r[i] = list[i];
	return r;
    }

    EditInfo checkboxInfo(String s, boolean b) {
	EditInfo ei = new EditInfo("", 0, -1, -1);
	ei.checkbox = new Checkbox(s, b);
	return ei;
    }

    public EditInfo getChipEditInfo(int n) {
	int items[] = editItems();
	if (n >= items.length)
	    return null;
	switch (items[n]) {
	case E_MODE: {
	    EditInfo ei = new EditInfo("Mode", 0, -1, -1);
	    ei.choice = new Choice();
	    ei.choice.add("Manual");
	    ei.choice.add("Auto");
	    ei.choice.select(autoMode ? 1 : 0);
	    return ei;
	}
	case E_OUTPUT: {
	    EditInfo ei = new EditInfo("Output", 0, -1, -1);
	    ei.choice = new Choice();
	    ei.choice.add("Analog");
	    ei.choice.add("PWM");
	    ei.choice.select(outputMode);
	    return ei;
	}
	case E_OMAX: return new EditInfo("Out Max (V)", outMax);
	case E_OMIN: return new EditInfo("Out Min (V)", outMin);
	case E_FREQ: return new EditInfo("PWM Frequency (Hz)", pwmFreq);
	case E_RAMP: return new EditInfo("Ramp Peak (V)", rampPeak);
	case E_DMIN: return new EditInfo("Min Duty (%)", dutyMin*100).setDimensionless();
	case E_DMAX: return new EditInfo("Max Duty (%)", dutyMax*100).setDimensionless();
	case E_HIGH: return new EditInfo("PWM High (V)", pwmHigh);
	case E_LOW: return new EditInfo("PWM Low (V)", pwmLow);
	case E_AW: return checkboxInfo("Anti-windup", antiWindup);
	case E_ADF: return checkboxInfo("Auto D Filter (\u03c4 = Td/10)", autoDFilter);
	case E_DTAU: return new EditInfo("D Filter \u03c4 (s), if not Auto", dTau);
	case E_XD: return checkboxInfo("Exclude D from Scope Scale", excludeDScale);
	case E_TH: return new EditInfo("Tune Step h (V)", tuneH);
	case E_TB: return new EditInfo("Tune Bias (V)", tuneBias);
	case E_THY: return new EditInfo("Tune Hysteresis (V)", tuneHyst);
	}
	return null;
    }

    public void setChipEditValue(int n, EditInfo ei) {
	int items[] = editItems();
	if (n >= items.length)
	    return;
	switch (items[n]) {
	case E_MODE: {
	    boolean a = ei.choice.getSelectedIndex() == 1;
	    if (a != autoMode) {
		autoMode = a;
		resetTune();	// switching to auto starts a new tune
		ei.newDialog = true;
	    }
	    break;
	}
	case E_OUTPUT: {
	    int m = ei.choice.getSelectedIndex();
	    if (m != outputMode) {
		outputMode = m;
		ei.newDialog = true;
	    }
	    break;
	}
	case E_OMAX: outMax = ei.value; break;
	case E_OMIN: outMin = ei.value; break;
	case E_FREQ: if (ei.value > 0) pwmFreq = ei.value; break;
	case E_RAMP: if (ei.value > 0) rampPeak = ei.value; break;
	case E_DMIN: dutyMin = Math.max(0, Math.min(100, ei.value))/100; break;
	case E_DMAX: dutyMax = Math.max(0, Math.min(100, ei.value))/100; break;
	case E_HIGH: pwmHigh = ei.value; break;
	case E_LOW: pwmLow = ei.value; break;
	case E_AW: antiWindup = ei.checkbox.getState(); break;
	case E_ADF: autoDFilter = ei.checkbox.getState(); break;
	case E_DTAU: if (ei.value >= 0) dTau = ei.value; break;
	case E_XD: excludeDScale = ei.checkbox.getState(); break;
	case E_TH: if (ei.value > 0) tuneH = ei.value; break;
	case E_TB: tuneBias = ei.value; break;
	case E_THY: if (ei.value >= 0) tuneHyst = ei.value; break;
	}
    }
}
