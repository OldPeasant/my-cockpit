import { ComponentFixture, TestBed } from '@angular/core/testing';
import { JournalHeatmapResponse } from '../../api/generated';
import { JournalHeatmap } from './journal-heatmap';

// 2026-10-05 is a Monday, 2026-10-25 a Sunday: three whole weeks from Monday.
const HEATMAP: JournalHeatmapResponse = {
  from: '2026-10-05',
  to: '2026-10-25',
  items: [{ date: '2026-10-06', mood: 1 }, { date: '2026-10-14' }, { date: '2026-10-25', mood: 5 }],
};

describe('JournalHeatmap', () => {
  let fixture: ComponentFixture<JournalHeatmap>;
  let root: HTMLElement;
  let selected: string[];

  function create(
    heatmap: JournalHeatmapResponse = HEATMAP,
    selectedDate = '2026-10-25',
    firstDay?: number,
  ): void {
    fixture = TestBed.createComponent(JournalHeatmap);
    fixture.componentRef.setInput('heatmap', heatmap);
    fixture.componentRef.setInput('selected', selectedDate);
    if (firstDay !== undefined) fixture.componentRef.setInput('firstDay', firstDay);
    selected = [];
    fixture.componentInstance.daySelected.subscribe((date) => selected.push(date));
    fixture.detectChanges();
    root = fixture.nativeElement as HTMLElement;
  }

  function day(date: string): HTMLButtonElement {
    return root.querySelector(`.day[data-date="${date}"]`) as HTMLButtonElement;
  }

  function text(el: Element | null): string {
    return el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  }

  it('shows one button per day in range, in week columns', () => {
    create();
    expect(root.querySelectorAll('.week')).toHaveLength(3);
    expect(root.querySelectorAll('button.day')).toHaveLength(21);
    expect(root.querySelectorAll('.day.outside')).toHaveLength(0);
    const firstWeek = [...root.querySelectorAll('.week')][0];
    expect(
      [...firstWeek.querySelectorAll('button.day')].map((b) => b.getAttribute('data-date')),
    ).toEqual([
      '2026-10-05',
      '2026-10-06',
      '2026-10-07',
      '2026-10-08',
      '2026-10-09',
      '2026-10-10',
      '2026-10-11',
    ]);
  });

  it('fills the days with an entry, with their mood level', () => {
    create();
    expect(day('2026-10-06').classList).toContain('entry');
    expect(day('2026-10-06').getAttribute('data-mood')).toBe('1');
    expect(day('2026-10-25').getAttribute('data-mood')).toBe('5');
    // An entry without a mood is still filled.
    expect(day('2026-10-14').classList).toContain('entry');
    expect(day('2026-10-14').getAttribute('data-mood')).toBe('none');
    // A day without an entry is not.
    expect(day('2026-10-07').classList).not.toContain('entry');
    expect(day('2026-10-07').hasAttribute('data-mood')).toBe(false);
  });

  it('labels each day with its date, whether it has an entry and its mood', () => {
    create();
    expect(day('2026-10-25').getAttribute('aria-label')).toBe(
      'Sunday, October 25, 2026, entry, mood 5 of 5',
    );
    expect(day('2026-10-14').getAttribute('aria-label')).toBe('Wednesday, October 14, 2026, entry');
    expect(day('2026-10-07').getAttribute('aria-label')).toBe(
      'Wednesday, October 7, 2026, no entry',
    );
    expect(day('2026-10-07').getAttribute('title')).toBe('Wednesday, October 7, 2026, no entry');
  });

  it('counts the entries in the caption', () => {
    create();
    expect(text(root.querySelector('.count'))).toBe('3 entries in the last 12 months');
    fixture.componentRef.setInput('heatmap', { ...HEATMAP, items: [HEATMAP.items[0]] });
    fixture.detectChanges();
    expect(text(root.querySelector('.count'))).toBe('1 entry in the last 12 months');
    fixture.componentRef.setInput('heatmap', { ...HEATMAP, items: [] });
    fixture.detectChanges();
    expect(text(root.querySelector('.count'))).toBe('No entries in the last 12 months');
    expect(root.querySelectorAll('button.day.entry')).toHaveLength(0);
  });

  it('shows a five-step mood legend', () => {
    create();
    const legend = root.querySelector('.legend') as HTMLElement;
    expect(
      [...legend.querySelectorAll('.day.entry')].map((d) => d.getAttribute('data-mood')),
    ).toEqual(['1', '2', '3', '4', '5']);
    expect(text(legend).startsWith('Low mood')).toBe(true);
    expect(text(legend).endsWith('Great')).toBe(true);
  });

  it('marks the selected day and makes it the only tab stop', () => {
    create(HEATMAP, '2026-10-14');
    expect(day('2026-10-14').classList).toContain('selected');
    expect(day('2026-10-14').getAttribute('aria-current')).toBe('date');
    expect(day('2026-10-14').tabIndex).toBe(0);
    expect(day('2026-10-25').hasAttribute('aria-current')).toBe(false);
    expect(root.querySelectorAll('button.day[tabindex="0"]')).toHaveLength(1);
  });

  it('makes the last day the tab stop when the selected day is not shown', () => {
    create(HEATMAP, '2024-10-25');
    expect(root.querySelector('.day.selected')).toBeNull();
    expect(day('2026-10-25').tabIndex).toBe(0);
    expect(root.querySelectorAll('button.day[tabindex="0"]')).toHaveLength(1);
  });

  it('emits the day clicked', () => {
    create();
    day('2026-10-07').click();
    day('2026-10-25').click();
    expect(selected).toEqual(['2026-10-07', '2026-10-25']);
  });

  it('pads the weeks outside the range and starts them on the configured day', () => {
    // Wednesday to Tuesday, weeks from Sunday.
    create({ from: '2026-10-07', to: '2026-10-13', items: [] }, '2026-10-13', 7);
    const weeks = [...root.querySelectorAll('.week')];
    expect(weeks).toHaveLength(2);
    expect(weeks[0].querySelectorAll('.day.outside')).toHaveLength(3);
    expect(weeks[1].querySelectorAll('.day.outside')).toHaveLength(4);
    expect(weeks[0].querySelector('button.day')?.getAttribute('data-date')).toBe('2026-10-07');
    const weekdays = [...root.querySelectorAll('.weekdays span')].map(text);
    expect(weekdays).toEqual(['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']);
    // Every other row label is shown.
    expect(root.querySelectorAll('.weekdays span.shown')).toHaveLength(4);
  });

  it('names the month in the week it begins', () => {
    create({ from: '2026-09-21', to: '2026-10-11', items: [] }, '2026-10-11');
    const months = [...root.querySelectorAll('.week .month')].map(text);
    expect(months).toEqual(['Sep', 'Oct', '']);
  });

  it('moves the focus between days with the arrow keys, within the range', () => {
    create(HEATMAP, '2026-10-14');
    const press = (from: string, key: string): KeyboardEvent => {
      const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
      day(from).dispatchEvent(event);
      return event;
    };

    expect(press('2026-10-14', 'ArrowDown').defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(day('2026-10-15'));
    press('2026-10-15', 'ArrowUp');
    expect(document.activeElement).toBe(day('2026-10-14'));
    press('2026-10-14', 'ArrowRight');
    expect(document.activeElement).toBe(day('2026-10-21'));
    press('2026-10-21', 'ArrowLeft');
    expect(document.activeElement).toBe(day('2026-10-14'));

    // Beyond the range: the focus stays.
    day('2026-10-25').focus();
    expect(press('2026-10-25', 'ArrowRight').defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(day('2026-10-25'));
    day('2026-10-05').focus();
    press('2026-10-05', 'ArrowUp');
    expect(document.activeElement).toBe(day('2026-10-05'));

    // Other keys are left to the browser.
    expect(press('2026-10-05', 'Enter').defaultPrevented).toBe(false);
    expect(press('2026-10-05', 'Home').defaultPrevented).toBe(false);
  });
});
