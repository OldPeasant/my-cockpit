import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { NotificationService } from './notification.service';

describe('NotificationService', () => {
  let snackBar: { open: ReturnType<typeof vi.fn> };
  let notifications: NotificationService;

  beforeEach(() => {
    snackBar = { open: vi.fn() };
    TestBed.configureTestingModule({ providers: [{ provide: MatSnackBar, useValue: snackBar }] });
    notifications = TestBed.inject(NotificationService);
  });

  it('shows an info message in a dismissible snackbar', () => {
    notifications.info('Task saved.');

    expect(snackBar.open).toHaveBeenCalledExactlyOnceWith('Task saved.', 'Dismiss', {
      duration: 4000,
    });
  });

  it('shows an error message longer and announces it assertively', () => {
    notifications.error('The server cannot be reached.');

    expect(snackBar.open).toHaveBeenCalledExactlyOnceWith(
      'The server cannot be reached.',
      'Dismiss',
      { duration: 8000, politeness: 'assertive' },
    );
  });
});

describe('NotificationService with the real MatSnackBar', () => {
  it('renders the error message and the dismiss action', async () => {
    const notifications = TestBed.inject(NotificationService);

    notifications.error('Validation failed.');
    await new Promise((resolve) => setTimeout(resolve));

    const container = document.querySelector('mat-snack-bar-container');
    expect(container?.textContent).toContain('Validation failed.');
    expect(container?.textContent).toContain('Dismiss');
    TestBed.inject(MatSnackBar).dismiss();
  });
});
