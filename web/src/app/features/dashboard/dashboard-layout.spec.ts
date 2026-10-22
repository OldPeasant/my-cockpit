import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NotificationService, provideHttp } from '../../core/http';
import { fakeSettings } from '../settings/testing';
import {
  DashboardLayout,
  DEFAULT_LAYOUT,
  LAYOUT_SETTING,
  moveWidget,
  parseLayout,
  serializeLayout,
  setVisible,
  WIDGET_IDS,
} from './dashboard-layout';

const SETTINGS = '/api/v1/settings';
const tick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve));

describe('dashboard layout', () => {
  it('defaults to every widget, shown, in the default order', () => {
    expect(parseLayout(undefined)).toEqual(DEFAULT_LAYOUT);
    expect(parseLayout([])).toEqual(DEFAULT_LAYOUT);
    expect(parseLayout('today')).toEqual(DEFAULT_LAYOUT);
    expect(DEFAULT_LAYOUT.map((slot) => slot.id)).toEqual([...WIDGET_IDS]);
  });

  it('reads the stored order and hidden widgets, then appends the missing ones', () => {
    const layout = parseLayout(['pinnedNotes', 'today:hidden', 'inbox']);
    expect(layout.slice(0, 3)).toEqual([
      { id: 'pinnedNotes', visible: true },
      { id: 'today', visible: false },
      { id: 'inbox', visible: true },
    ]);
    expect(layout.slice(3).map((slot) => slot.id)).toEqual(
      WIDGET_IDS.filter((id) => !['pinnedNotes', 'today', 'inbox'].includes(id)),
    );
    expect(layout.slice(3).every((slot) => slot.visible)).toBe(true);
  });

  it('skips unknown, repeated and non-text entries', () => {
    const layout = parseLayout([
      'weather',
      'inbox',
      'inbox:hidden',
      7,
      null,
      'today:hidden:hidden',
    ]);
    expect(layout[0]).toEqual({ id: 'inbox', visible: true });
    expect(layout).toHaveLength(WIDGET_IDS.length);
    expect(layout.find((slot) => slot.id === 'today')).toEqual({ id: 'today', visible: true });
  });

  it('round-trips through the setting value', () => {
    const layout = setVisible(moveWidget(DEFAULT_LAYOUT, 'readLater', -10), 'today', false);
    const stored = serializeLayout(layout);
    expect(stored[0]).toBe('readLater');
    expect(stored).toContain('today:hidden');
    expect(parseLayout(stored)).toEqual(layout);
  });

  it('moves a widget by an offset, clamped to the ends', () => {
    const ids = (layout: { id: string }[]) => layout.map((slot) => slot.id);
    expect(ids(moveWidget(DEFAULT_LAYOUT, 'inbox', 1)).slice(0, 3)).toEqual([
      'today',
      'upcoming',
      'inbox',
    ]);
    expect(ids(moveWidget(DEFAULT_LAYOUT, 'inbox', -5))[0]).toBe('inbox');
    expect(ids(moveWidget(DEFAULT_LAYOUT, 'today', 99)).at(-1)).toBe('today');
    expect(moveWidget(DEFAULT_LAYOUT, 'today', 0)).toEqual(DEFAULT_LAYOUT);
  });

  it('leaves a layout without the widget unchanged, and never changes its input', () => {
    const partial = DEFAULT_LAYOUT.filter((slot) => slot.id !== 'inbox');
    expect(moveWidget(partial, 'inbox', 1)).toEqual(partial);
    const before = [...DEFAULT_LAYOUT];
    moveWidget(DEFAULT_LAYOUT, 'today', 3);
    setVisible(DEFAULT_LAYOUT, 'today', false);
    expect(DEFAULT_LAYOUT).toEqual(before);
  });

  it('shows or hides only the given widget, and can show it again', () => {
    const hidden = setVisible(DEFAULT_LAYOUT, 'readLater', false);
    expect(hidden.filter((slot) => !slot.visible).map((slot) => slot.id)).toEqual(['readLater']);
    expect(hidden.map((slot) => slot.id)).toEqual([...WIDGET_IDS]);
    expect(setVisible(hidden, 'readLater', true)).toEqual(DEFAULT_LAYOUT);
  });

  it('skips a bare hidden suffix and keeps a fully hidden layout hidden', () => {
    expect(parseLayout([':hidden', ''])).toEqual(DEFAULT_LAYOUT);
    const allHidden = WIDGET_IDS.map((id) => `${id}:hidden`);
    expect(parseLayout(allHidden).every((slot) => !slot.visible)).toBe(true);
    expect(serializeLayout(parseLayout(allHidden))).toEqual(allHidden);
  });
});

describe('DashboardLayout', () => {
  let layout: DashboardLayout;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttp(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: { error: vi.fn(), info: vi.fn() } },
      ],
    });
    layout = TestBed.inject(DashboardLayout);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function loadSettings(stored: unknown[] = []): Promise<void> {
    const loading = layout.ensureLoaded();
    http
      .expectOne({ method: 'GET', url: SETTINGS })
      .flush(fakeSettings({ [LAYOUT_SETTING]: stored }), { headers: { ETag: '"3"' } });
    await loading;
  }

  it('shows the default layout until the settings are loaded, then the stored one', async () => {
    expect(layout.loaded()).toBe(false);
    expect(layout.slots()).toEqual(DEFAULT_LAYOUT);

    await loadSettings(['inbox', 'today:hidden']);

    expect(layout.loaded()).toBe(true);
    expect(layout.visible()[0]).toBe('inbox');
    expect(layout.visible()).not.toContain('today');
  });

  it('saves a hidden widget in the settings with the ETag, optimistically', async () => {
    await loadSettings();

    const saved = layout.setVisible('upcoming', false);
    await tick();
    expect(layout.visible()).not.toContain('upcoming');
    const req = http.expectOne({ method: 'PATCH', url: SETTINGS });
    expect(req.request.headers.get('If-Match')).toBe('"3"');
    const stored = serializeLayout(setVisible(DEFAULT_LAYOUT, 'upcoming', false));
    expect(req.request.body).toEqual({ [LAYOUT_SETTING]: stored });
    req.flush(fakeSettings({ [LAYOUT_SETTING]: stored }), { headers: { ETag: '"4"' } });
    await saved;

    expect(layout.slots()).toEqual(parseLayout(stored));
  });

  it('saves a new order, each change building on the previous one', async () => {
    await loadSettings(['pinnedNotes']);

    const first = layout.move('inbox', -1);
    await tick();
    const firstReq = http.expectOne({ method: 'PATCH', url: SETTINGS });
    const afterFirst = firstReq.request.body[LAYOUT_SETTING] as string[];
    expect(afterFirst.slice(0, 3)).toEqual(['pinnedNotes', 'inbox', 'today']);
    firstReq.flush(fakeSettings({ [LAYOUT_SETTING]: afterFirst }), { headers: { ETag: '"4"' } });
    await first;

    const second = layout.move('inbox', -1);
    await tick();
    const secondReq = http.expectOne({ method: 'PATCH', url: SETTINGS });
    expect(secondReq.request.headers.get('If-Match')).toBe('"4"');
    expect((secondReq.request.body[LAYOUT_SETTING] as string[]).slice(0, 2)).toEqual([
      'inbox',
      'pinnedNotes',
    ]);
    secondReq.flush(fakeSettings({ [LAYOUT_SETTING]: secondReq.request.body[LAYOUT_SETTING] }));
    await second;
  });

  it('loads the settings before a change, so the stored layout is not overwritten', async () => {
    const saved = layout.setVisible('today', false);
    http
      .expectOne({ method: 'GET', url: SETTINGS })
      .flush(fakeSettings({ [LAYOUT_SETTING]: ['readLater:hidden'] }), {
        headers: { ETag: '"8"' },
      });
    await tick();
    await tick();

    const req = http.expectOne({ method: 'PATCH', url: SETTINGS });
    const stored = req.request.body[LAYOUT_SETTING] as string[];
    expect(stored[0]).toBe('readLater:hidden');
    expect(stored).toContain('today:hidden');
    req.flush(fakeSettings({ [LAYOUT_SETTING]: stored }));
    await saved;
  });

  it('rolls a refused change back', async () => {
    await loadSettings();

    const saved = layout.setVisible('inbox', false);
    await tick();
    expect(layout.visible()).not.toContain('inbox');
    http
      .expectOne({ method: 'PATCH', url: SETTINGS })
      .flush({ title: 'Bad Request', status: 400 }, { status: 400, statusText: 'Bad Request' });
    await expect(saved).rejects.toBeTruthy();

    expect(layout.visible()).toContain('inbox');
  });

  it('does not load the settings again once they are loaded', async () => {
    await loadSettings(['inbox']);

    await layout.ensureLoaded();
    // http.verify() in afterEach fails on a second GET.
    expect(layout.visible()[0]).toBe('inbox');
  });

  it('fails ensureLoaded when the settings cannot be loaded, keeping the default layout', async () => {
    const loading = layout.ensureLoaded();
    http
      .expectOne({ method: 'GET', url: SETTINGS })
      .flush({ title: 'Boom', status: 500 }, { status: 500, statusText: 'Server Error' });

    await expect(loading).rejects.toBeTruthy();
    expect(layout.loaded()).toBe(false);
    expect(layout.slots()).toEqual(DEFAULT_LAYOUT);
  });

  it('resets the layout to the setting default', async () => {
    await loadSettings(['inbox:hidden']);

    const reset = layout.reset();
    await tick();
    const req = http.expectOne({ method: 'PATCH', url: SETTINGS });
    expect(req.request.body).toEqual({ [LAYOUT_SETTING]: null });
    req.flush(fakeSettings({ [LAYOUT_SETTING]: [] }));
    await reset;

    expect(layout.slots()).toEqual(DEFAULT_LAYOUT);
  });
});
