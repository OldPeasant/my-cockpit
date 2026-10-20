import { inject, Injectable } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';

/** Short, non-blocking messages to the user (Material snackbar). */
@Injectable({ providedIn: 'root' })
export class NotificationService {
  private readonly snackBar = inject(MatSnackBar);

  info(message: string): void {
    this.snackBar.open(message, $localize`:@@notification.dismiss:Dismiss`, { duration: 4000 });
  }

  error(message: string): void {
    this.snackBar.open(message, $localize`:@@notification.dismiss:Dismiss`, {
      duration: 8000,
      politeness: 'assertive',
    });
  }
}
