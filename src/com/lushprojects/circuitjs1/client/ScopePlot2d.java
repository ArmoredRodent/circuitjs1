package com.lushprojects.circuitjs1.client;

import com.google.gwt.canvas.client.Canvas;
import com.google.gwt.canvas.dom.client.Context2d;
import java.util.Vector;

class ScopePlot2d {
    boolean enabled;
    boolean plotXY;
    double scaleX, scaleY;

    // X/Y axis plot indices (into scope.plots)
    int plotX = 0, plotY = 1;
    // Modulator plot indices (-1 = none)
    int plotBrightness = -1;
    int plotColorR = -1, plotColorG = -1, plotColorB = -1;
    // Auto-scales for modulator plots
    double scaleBrightness = 5, scaleR = 5, scaleG = 5, scaleB = 5;

    private Canvas imageCanvas;
    private Context2d imageContext;
    private Canvas tmpCanvas;		// scratch copy of the trail image, used when rescaling it
    boolean keepOnResize;		// set by Scope.setRect(): scale the trail to the new size, don't clear it
    private Context2d tmpContext;
    private int draw_ox, draw_oy;
    private int alphaCounter;
    int trailPersistence = 0;
    static final int DEFAULT_TRAIL_PERSISTENCE = 0;
    double lastTrailSimTime = -1;
    // measured sim time per drawn frame, to express the frame-based default fade in sim time
    double simPerFrame = 0;
    private double lastFrameSimTime = -1;

    Scope scope;

    // ---- Auto-fit (Auto / Auto Max Scale modes) ----
    // The plot is fitted to the data seen over the last one to two "fit windows" (the scope's
    // horizontal time span), so it follows the trace and shrinks again after a transient.
    // Index [0] is X, [1] is Y.
    boolean includeOrigin = true;	// keep (0,0) in the fitted range so the axes stay visible
    boolean alignX = false;		// share the X range with other scopes that also have alignX set
    static final double FIT_MARGIN = .1;	// fraction of width/height left empty on each edge
    static final double SHRINK_RATIO = .5;	// refit when the data uses less than this much of the range
    static final double EXPAND_TOL = .05;	// a point may overshoot the range by this much (fraction of
					// the range, drawn into the edge margin) before we refit
    private int shrinkVotes;		// consecutive window checks that found the range too big
    private double curLo[] = new double[2], curHi[] = new double[2];	// data range, current window
    private double prevLo[] = new double[2], prevHi[] = new double[2];	// data range, previous window
    private boolean haveCur, havePrev;
    private double windowStart = -1;
    private double wantLo[] = new double[2], wantHi[] = new double[2];	// range this scope wants
    private boolean haveWant;
    private double dispLo[] = new double[2], dispHi[] = new double[2];	// range being displayed
    private boolean haveDisp;

    ScopePlot2d(Scope scope) {
	this.scope = scope;
	scaleX = 5;
	scaleY = .1;
    }

    void allocImage() {
	if (!enabled)
	    return;
	int ow = 0, oh = 0;
	if (imageCanvas == null) {
	    imageCanvas = Canvas.createIfSupported();
	    imageContext = imageCanvas.getContext2d();
	} else {
	    ow = imageCanvas.getCoordinateSpaceWidth();
	    oh = imageCanvas.getCoordinateSpaceHeight();
	}
	int w = scope.rect.width, h = scope.rect.height;
	// The pixel mapping is proportional to the scope size (margins are a fraction of it), so on a
	// resize the old trail stays correct if it is simply scaled to the new size.
	boolean keep = keepOnResize && haveDisp && ow > 1 && oh > 1 && (ow != w || oh != h);
	if (keep) {
	    if (tmpCanvas == null) {
		tmpCanvas = Canvas.createIfSupported();
		tmpContext = tmpCanvas.getContext2d();
	    }
	    tmpCanvas.setCoordinateSpaceWidth(ow);
	    tmpCanvas.setCoordinateSpaceHeight(oh);
	    tmpContext.drawImage(imageCanvas.getCanvasElement(), 0, 0);
	}
	imageCanvas.setWidth(w + "PX");
	imageCanvas.setHeight(h + "PX");
	imageCanvas.setCoordinateSpaceWidth(w);
	imageCanvas.setCoordinateSpaceHeight(h);
	if (!keep) {
	    clearView();
	    return;
	}
	fillBackground();
	double sx = w / (double) ow, sy = h / (double) oh;
	imageContext.save();
	imageContext.scale(sx, sy);
	imageContext.drawImage(tmpCanvas.getCanvasElement(), 0, 0);
	imageContext.restore();
	if (draw_ox != -1) {
	    draw_ox = (int) (draw_ox * sx);
	    draw_oy = (int) (draw_oy * sy);
	}
    }

    void clearView() {
	fillBackground();
	draw_ox = draw_oy = -1;
    }

    void fillBackground() {
	if (imageContext != null) {
	    if (scope.app.isPrintable()) {
		imageContext.setFillStyle("#eee");
	    } else {
		imageContext.setFillStyle("#111");
	    }
	    imageContext.fillRect(0, 0, scope.rect.width - 1, scope.rect.height - 1);
	}
    }

    double calcGridPx(int width, int height) {
	int m = width < height ? width : height;
	return ((double)(m) / 2) / ((double)(scope.manDivisions) / 2 + 0.05);
    }

    // Draw a segment from (ox,oy) to (x2,y2) with the given color and alpha.
    void drawSegment(int ox, int oy, int x2, int y2, String color, double alpha) {
	if (alpha != 1.0)
	    imageContext.setGlobalAlpha(alpha);
	imageContext.setStrokeStyle(color);
	imageContext.beginPath();
	imageContext.moveTo(ox, oy);
	imageContext.lineTo(x2, y2);
	imageContext.stroke();
	if (alpha != 1.0)
	    imageContext.setGlobalAlpha(1.0);
    }

    void drawTo(int x2, int y2) {
	if (draw_ox == -1) {
	    draw_ox = x2;
	    draw_oy = y2;
	    return;
	}
	String color = scope.app.isPrintable() ? "#000000" : "#ffffff";
	drawSegment(draw_ox, draw_oy, x2, y2, color, 1.0);
	draw_ox = x2;
	draw_oy = y2;
    }

    void drawTo(int x2, int y2, String color, double alpha) {
	if (draw_ox == -1) {
	    draw_ox = x2;
	    draw_oy = y2;
	    return;
	}
	drawSegment(draw_ox, draw_oy, x2, y2, color, alpha);
	draw_ox = x2;
	draw_oy = y2;
    }

    // Compute the draw color from R/G/B modulator plots.
    String computeColor() {
	if (plotColorR < 0 && plotColorG < 0 && plotColorB < 0)
	    return scope.app.isPrintable() ? "#000000" : "#ffffff";
	int r = 0, g = 0, b = 0;
	if (plotColorR >= 0 && plotColorR < scope.plots.size()) {
	    double rv = scope.plots.get(plotColorR).lastValue;
	    while (rv > scaleR) scaleR *= 2;
	    r = (int) Math.max(0, Math.min(255, (rv / scaleR) * 255));
	}
	if (plotColorG >= 0 && plotColorG < scope.plots.size()) {
	    double gv = scope.plots.get(plotColorG).lastValue;
	    while (gv > scaleG) scaleG *= 2;
	    g = (int) Math.max(0, Math.min(255, (gv / scaleG) * 255));
	}
	if (plotColorB >= 0 && plotColorB < scope.plots.size()) {
	    double bv = scope.plots.get(plotColorB).lastValue;
	    while (bv > scaleB) scaleB *= 2;
	    b = (int) Math.max(0, Math.min(255, (bv / scaleB) * 255));
	}
	return "rgb(" + r + "," + g + "," + b + ")";
    }

    // Compute draw alpha from the brightness modulator plot (0 = off, 1 = full).
    double computeAlpha() {
	if (plotBrightness < 0 || plotBrightness >= scope.plots.size())
	    return 1.0;
	double bv = Math.abs(scope.plots.get(plotBrightness).lastValue);
	while (bv > scaleBrightness) scaleBrightness *= 2;
	return scaleBrightness > 0 ? bv / scaleBrightness : 0;
    }

    // Clamp plotX/plotY to valid range.
    int validPlotIndex(int idx, int defaultIdx) {
	if (scope.plots.size() == 0) return 0;
	if (idx < 0 || idx >= scope.plots.size()) return Math.min(defaultIdx, scope.plots.size() - 1);
	return idx;
    }

    void timeStep() {
	if (imageContext == null || scope.plots.size() < 1)
	    return;
	int px = validPlotIndex(plotX, 0);
	int py = validPlotIndex(plotY, Math.min(1, scope.plots.size() - 1));
	double v = scope.plots.get(px).lastValue;
	double yval = scope.plots.get(py).lastValue;
	int x, y;
	if (!scope.isManualScale()) {
	    fitPoint(v, yval);
	    x = toPixel(0, v);
	    y = toPixel(1, yval);
	} else {
	    double gridPx = calcGridPx(scope.rect.width, scope.rect.height);
	    x = (int)(scope.rect.width  * .499 + (v    / scope.plots.get(px).manScale) * gridPx + gridPx * scope.manDivisions * (double)(scope.plots.get(px).manVPosition) / (double)(Scope.V_POSITION_STEPS));
	    y = (int)(scope.rect.height * .499 - (yval / scope.plots.get(py).manScale) * gridPx - gridPx * scope.manDivisions * (double)(scope.plots.get(py).manVPosition) / (double)(Scope.V_POSITION_STEPS));
	}
	String color = computeColor();
	double alpha = computeAlpha();
	drawTo(x, y, color, alpha);
    }

    void maxScale() {
	double x = 1e-8;
	scope.scale[Scope.UNITS_V]    *= x;
	scope.scale[Scope.UNITS_A]    *= x;
	scope.scale[Scope.UNITS_OHMS] *= x;
	scope.scale[Scope.UNITS_W]    *= x;
	scope.scale[Scope.UNITS_C]    *= x;
	// X/Y auto-fit just starts over (used to shrink scaleX/scaleY to nothing and regrow)
	resetFit();
	clearView();
	scaleBrightness *= x;
	scaleR *= x;
	scaleG *= x;
	scaleB *= x;
    }

    void resetFit() {
	haveCur = havePrev = haveWant = haveDisp = false;
	windowStart = -1;
	shrinkVotes = 0;
    }

    // sim time covered by one fit window: the scope's horizontal span (width * speed timesteps)
    double fitWindow() {
	return Math.max(scope.rect.width, 1) * scope.speed * scope.sim.maxTimeStep;
    }

    // Record a new X/Y point and refit if it falls outside the displayed range, or if the data
    // has shrunk well inside it (checked once per window, so a spike rolls off after 1-2 windows).
    void fitPoint(double xv, double yv) {
	double t = scope.sim.t;
	if (windowStart < 0 || t < windowStart) {
	    resetFit();
	    windowStart = t;
	}
	boolean rolled = false;
	if (t - windowStart >= fitWindow()) {
	    for (int a = 0; a < 2; a++) {
		prevLo[a] = curLo[a];
		prevHi[a] = curHi[a];
	    }
	    havePrev = haveCur;
	    haveCur = false;
	    windowStart = t;
	    rolled = true;
	}
	if (!haveCur) {
	    curLo[0] = curHi[0] = xv;
	    curLo[1] = curHi[1] = yv;
	    haveCur = true;
	} else {
	    curLo[0] = Math.min(curLo[0], xv); curHi[0] = Math.max(curHi[0], xv);
	    curLo[1] = Math.min(curLo[1], yv); curHi[1] = Math.max(curHi[1], yv);
	}
	// Expand when a point leaves the range by more than EXPAND_TOL.  Without the tolerance every
	// new extreme, however tiny (sampling jitter, a slowly settling Q-point), forced a refit.
	boolean refit = !haveDisp || outside(0, xv) || outside(1, yv);
	if (rolled && !refit) {
	    // Shrink only when two window checks in a row agree the data has become much smaller,
	    // so a trace slower than one window doesn't shrink and re-expand every window.
	    calcWant();
	    boolean small = false;
	    for (int a = 0; a < 2; a++)
		if (wantHi[a] - wantLo[a] < SHRINK_RATIO * (dispHi[a] - dispLo[a]))
		    small = true;
	    shrinkVotes = small ? shrinkVotes + 1 : 0;
	    if (shrinkVotes >= 2)
		refit = true;
	}
	if (refit) {
	    shrinkVotes = 0;
	    refit();
	}
    }

    boolean outside(int a, double v) {
	double tol = EXPAND_TOL * (dispHi[a] - dispLo[a]);
	return v < dispLo[a] - tol || v > dispHi[a] + tol;
    }

    // range this scope wants on each axis: data from the last two windows (+ origin if asked)
    void calcWant() {
	for (int a = 0; a < 2; a++) {
	    double lo = curLo[a], hi = curHi[a];
	    if (havePrev) {
		lo = Math.min(lo, prevLo[a]);
		hi = Math.max(hi, prevHi[a]);
	    }
	    if (includeOrigin) {
		lo = Math.min(lo, 0);
		hi = Math.max(hi, 0);
	    }
	    // a flat trace (e.g. a single Q-point) still needs some range to draw in
	    if (hi - lo <= 1e-6 * Math.max(Math.abs(lo), Math.abs(hi))) {
		double c = (hi + lo) / 2;
		double h = (c != 0) ? Math.abs(c) * .1 : 1e-3;
		lo = c - h;
		hi = c + h;
	    }
	    wantLo[a] = lo;
	    wantHi[a] = hi;
	}
	haveWant = true;
    }

    void refit() {
	calcWant();
	double lo = wantLo[0], hi = wantHi[0];
	// With "Align X" checked, X is shared with the other aligned scopes plotting the same kind
	// of value (Vce with Vce...), so their curves line up.  Y always stays per scope.
	Vector<ScopePlot2d> group = getXGroup();
	for (ScopePlot2d o : group)
	    if (o.haveWant) {
		lo = Math.min(lo, o.wantLo[0]);
		hi = Math.max(hi, o.wantHi[0]);
	    }
	for (ScopePlot2d o : group)
	    if (o.haveDisp)
		o.setDisp(lo, hi, o.dispLo[1], o.dispHi[1]);
	setDisp(lo, hi, wantLo[1], wantHi[1]);
    }

    // Change the displayed range.  The trail already drawn is rescaled to the new range rather
    // than cleared, so a refit doesn't wipe the screen.
    void setDisp(double xlo, double xhi, double ylo, double yhi) {
	if (!haveDisp) {
	    dispLo[0] = xlo; dispHi[0] = xhi;
	    dispLo[1] = ylo; dispHi[1] = yhi;
	    haveDisp = true;
	    clearView();
	    return;
	}
	if (dispLo[0] == xlo && dispHi[0] == xhi && dispLo[1] == ylo && dispHi[1] == yhi)
	    return;
	double oLo[] = { dispLo[0], dispLo[1] }, oHi[] = { dispHi[0], dispHi[1] };
	dispLo[0] = xlo; dispHi[0] = xhi;
	dispLo[1] = ylo; dispHi[1] = yhi;
	remapImage(oLo, oHi);
    }

    // Redraw the trail image from the old range (oLo/oHi) into the current one.  Each axis is a
    // linear map, so this is one scaled drawImage.  A huge zoom would just smear old pixels, so
    // in that case start clean instead.
    void remapImage(double oLo[], double oHi[]) {
	if (imageContext == null)
	    return;
	double ax = (oHi[0] - oLo[0]) / (dispHi[0] - dispLo[0]);
	double ay = (oHi[1] - oLo[1]) / (dispHi[1] - dispLo[1]);
	if (ax > 4 || ay > 4 || ax < .05 || ay < .05) {
	    clearView();
	    return;
	}
	// new pixel = a * old pixel + b, matched at the old range's low end
	double bx = toPixelD(0, oLo[0]) - ax * pixelOf(0, oLo[0], oLo[0], oHi[0]);
	double by = toPixelD(1, oLo[1]) - ay * pixelOf(1, oLo[1], oLo[1], oHi[1]);
	int w = scope.rect.width, h = scope.rect.height;
	if (tmpCanvas == null) {
	    tmpCanvas = Canvas.createIfSupported();
	    tmpContext = tmpCanvas.getContext2d();
	}
	if (tmpCanvas.getCoordinateSpaceWidth() != w || tmpCanvas.getCoordinateSpaceHeight() != h) {
	    tmpCanvas.setCoordinateSpaceWidth(w);
	    tmpCanvas.setCoordinateSpaceHeight(h);
	}
	tmpContext.clearRect(0, 0, w, h);
	tmpContext.drawImage(imageCanvas.getCanvasElement(), 0, 0);
	fillBackground();
	imageContext.save();
	imageContext.translate(bx, by);
	imageContext.scale(ax, ay);
	imageContext.drawImage(tmpCanvas.getCanvasElement(), 0, 0);
	imageContext.restore();
	if (draw_ox != -1) {
	    draw_ox = (int) (ax * draw_ox + bx);
	    draw_oy = (int) (ay * draw_oy + by);
	}
    }

    // what kind of value is on the X axis, for X sharing
    String xKey() {
	ScopePlot p = scope.plots.get(validPlotIndex(plotX, 0));
	return (p.elm instanceof TransistorElm ? "t" : "e") + p.value + "/" + p.units;
    }

    boolean canShareX() {
	return alignX && enabled && !scope.isManualScale() && scope.plots.size() > 0;
    }

    // other auto-fit X/Y scopes (docked or floating) with the same kind of X value
    Vector<ScopePlot2d> getXGroup() {
	Vector<ScopePlot2d> group = new Vector<ScopePlot2d>();
	if (!canShareX())
	    return group;
	String key = xKey();
	ScopeManager sm = scope.app.scopeManager;
	for (int i = 0; i < sm.scopeCount; i++)
	    addToGroup(group, sm.scopes[i], key);
	if (scope.app.scopeElmArr != null)
	    for (int i = 0; i < scope.app.scopeElmArr.length; i++)
		addToGroup(group, scope.app.scopeElmArr[i].elmScope, key);
	return group;
    }

    void addToGroup(Vector<ScopePlot2d> group, Scope s, String key) {
	if (s == null || s == scope || !s.plot2d.canShareX() || !key.equals(s.plot2d.xKey()))
	    return;
	group.add(s.plot2d);
    }

    // value -> pixel within the scope for a given range, leaving FIT_MARGIN empty on each edge
    double pixelOf(int a, double v, double lo, double hi) {
	double f = (v - lo) / (hi - lo);
	if (a == 0) {
	    double m = scope.rect.width * FIT_MARGIN;
	    return m + f * (scope.rect.width - 2 * m);
	}
	double m = scope.rect.height * FIT_MARGIN;
	return scope.rect.height - m - f * (scope.rect.height - 2 * m);
    }

    double toPixelD(int a, double v) {
	return pixelOf(a, v, dispLo[a], dispHi[a]);
    }

    int toPixel(int a, double v) {
	return (int) toPixelD(a, v);
    }

    double fromPixel(int a, int px) {
	double f;
	if (a == 0) {
	    double m = scope.rect.width * FIT_MARGIN;
	    f = (px - m) / (scope.rect.width - 2 * m);
	} else {
	    double m = scope.rect.height * FIT_MARGIN;
	    f = (scope.rect.height - m - px) / (scope.rect.height - 2 * m);
	}
	return dispLo[a] + f * (dispHi[a] - dispLo[a]);
    }

    // Trail time constant (sim seconds) of the default fade: alpha .01 every 3 frames, so
    // 3/-ln(.99) ~= 298 frames.  0 if the sim hasn't run yet.
    double defaultTrailTime() {
	return simPerFrame * 3 / -Math.log(.99);
    }

    // trail time constant actually in use (sim seconds)
    double trailTime() {
	if (trailPersistence <= 0)
	    return defaultTrailTime();
	return trailPersistence * scope.sim.maxTimeStep;
    }

    // forExport suppresses mouse-driven UI chrome (settings wheel, hover value readout) that
    // shouldn't appear in an exported PNG/SVG -- mirrors Scope.draw(Graphics, boolean)'s own
    // forExport handling for the normal (non-2D) plot path
    void draw(Graphics g, boolean forExport) {
	if (imageContext == null)
	    return;
	g.context.save();
	g.context.translate(scope.rect.x, scope.rect.y);
	g.clipRect(0, 0, scope.rect.width, scope.rect.height);

	double ft = scope.sim.t;
	if (lastFrameSimTime >= 0 && ft > lastFrameSimTime) {
	    double d = ft - lastFrameSimTime;
	    simPerFrame = (simPerFrame == 0) ? d : simPerFrame * .95 + d * .05;
	}
	lastFrameSimTime = ft;
	alphaCounter++;
	if (alphaCounter > 2) {
	    alphaCounter = 0;
	    double fadeAlpha;
	    if (trailPersistence <= 0) {
		fadeAlpha = 0.01;
	    } else {
		// Sim-time exponential fade; time constant = trailPersistence * maxTimeStep (seconds).
		// Don't advance lastTrailSimTime until fadeAlpha is large enough; otherwise sub-pixel
		// alphas have no effect on an 8-bit canvas and the trail never fades at low speed.
		if (lastTrailSimTime < 0 || scope.sim.t < lastTrailSimTime)
		    lastTrailSimTime = scope.sim.t;
		double elapsed = scope.sim.t - lastTrailSimTime;
		double timeConst = trailPersistence * scope.sim.maxTimeStep;
		fadeAlpha = 1.0 - Math.exp(-elapsed / timeConst);
		if (fadeAlpha >= 3.0 / 255)
		    lastTrailSimTime = scope.sim.t;
		else
		    fadeAlpha = 0;
	    }
	    if (fadeAlpha > 0) {
		imageContext.setGlobalAlpha(fadeAlpha);
		if (scope.app.isPrintable()) {
		    imageContext.setFillStyle("#ffffff");
		} else {
		    imageContext.setFillStyle("#000000");
		}
		imageContext.fillRect(0, 0, scope.rect.width, scope.rect.height);
		imageContext.setGlobalAlpha(1.0);
	    }
	}

	g.context.drawImage(imageContext.getCanvas(), 0.0, 0.0);
	g.setColor(CircuitElm.whiteColor);
	g.fillOval(draw_ox - 2, draw_oy - 2, 5, 5);
	g.setColor(CircuitElm.positiveColor);
	if (scope.isManualScale()) {
	    g.drawLine(0, scope.rect.height / 2, scope.rect.width - 1, scope.rect.height / 2);
	    if (!plotXY)
		g.setColor(Color.yellow);
	    g.drawLine(scope.rect.width / 2, 0, scope.rect.width / 2, scope.rect.height - 1);
	} else if (haveDisp) {
	    // auto-fit: draw each axis only where zero is actually in view
	    if (dispLo[1] <= 0 && dispHi[1] >= 0) {
		int zy = toPixel(1, 0);
		g.drawLine(0, zy, scope.rect.width - 1, zy);
	    }
	    if (!plotXY)
		g.setColor(Color.yellow);
	    if (dispLo[0] <= 0 && dispHi[0] >= 0) {
		int zx = toPixel(0, 0);
		g.drawLine(zx, 0, zx, scope.rect.height - 1);
	    }
	}
	if (scope.isManualScale()) {
	    double gridPx = calcGridPx(scope.rect.width, scope.rect.height);
	    g.setColor("#404040");
	    for (int i = -scope.manDivisions; i <= scope.manDivisions; i++) {
		if (i != 0)
		    g.drawLine((int)(gridPx * i) + scope.rect.width / 2, 0, (int)(gridPx * i) + scope.rect.width / 2, scope.rect.height);
		g.drawLine(0, (int)(gridPx * i) + scope.rect.height / 2, scope.rect.width, (int)(gridPx * i) + scope.rect.height / 2);
	    }
	}
	scope.overlays.textY = 10;
	g.setColor(CircuitElm.whiteColor);
	if (scope.text != null)
	    scope.drawInfoText(g, scope.text);
	int px = validPlotIndex(plotX, 0);
	int py = validPlotIndex(plotY, Math.min(1, scope.plots.size() - 1));
	if (scope.showScale && scope.plots.size() >= 1 && scope.isManualScale() && px < scope.plots.size() && py < scope.plots.size()) {
	    ScopePlot spx = scope.plots.get(px);
	    ScopePlot spy = scope.plots.get(py);
	    scope.drawInfoText(g, "X=" + spx.getUnitText(spx.manScale) + "/div, Y=" + spy.getUnitText(spy.manScale) + "/div");
	}
	g.context.restore();
	if (!forExport)
	    scope.drawSettingsWheel(g);
	if (!forExport && !scope.app.dialogIsShowing() && scope.rect.contains(scope.app.mouse.mouseCursorX, scope.app.mouse.mouseCursorY) && scope.plots.size() >= 1 && px < scope.plots.size() && py < scope.plots.size() &&
		(scope.isManualScale() || haveDisp)) {
	    double gridPx = calcGridPx(scope.rect.width, scope.rect.height);
	    String[] info = new String[2];
	    ScopePlot spx = scope.plots.get(px);
	    ScopePlot spy = scope.plots.get(py);
	    double xValue, yValue;
	    if (scope.isManualScale()) {
		xValue = spx.manScale * ((double)(scope.app.mouse.mouseCursorX - scope.rect.x - scope.rect.width  / 2) / gridPx - scope.manDivisions * spx.manVPosition / (double)(Scope.V_POSITION_STEPS));
		yValue = spy.manScale * ((double)(-scope.app.mouse.mouseCursorY + scope.rect.y + scope.rect.height / 2) / gridPx - scope.manDivisions * spy.manVPosition / (double)(Scope.V_POSITION_STEPS));
	    } else {
		xValue = fromPixel(0, scope.app.mouse.mouseCursorX - scope.rect.x);
		yValue = fromPixel(1, scope.app.mouse.mouseCursorY - scope.rect.y);
	    }
	    info[0] = spx.getUnitText(xValue);
	    info[1] = spy.getUnitText(yValue);
	    scope.drawCursorInfo(g, info, 2, scope.app.mouse.mouseCursorX, true);
	}
    }
}
