import { HttpContext } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  inject,
  Injectable,
  OnInit,
  signal,
} from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import {
  MAT_DIALOG_DATA,
  MatDialog,
  MatDialogModule,
  MatDialogRef,
} from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatRadioModule } from '@angular/material/radio';
import { MatSelectModule } from '@angular/material/select';
import { firstValueFrom } from 'rxjs';
import {
  Api,
  listMailAccounts,
  MailAccountResponse,
  sendEventInvitations,
  SendInvitationsRequest,
  SendInvitationsResponse,
} from '../../../api/generated';
import { ApiError, SILENT_ERRORS } from '../../../core/http';

export type InvitationMethod = SendInvitationsRequest['method'];

export interface InvitationDialogData {
  /** The event to send: a series master goes out with its overrides, an override on its own. */
  readonly eventId: string;
  readonly title: string;
  /** The guests of the saved event (the organizer among them gets nothing). */
  readonly attendees: readonly string[];
}

/** The requests of the {@link InvitationDialog}. Errors are silent: the dialog shows them. */
@Injectable({ providedIn: 'root' })
export class InvitationService {
  private readonly api = inject(Api);

  async accounts(): Promise<MailAccountResponse[]> {
    return (await this.api.invoke(listMailAccounts, undefined, silent())).items;
  }

  send(
    eventId: string,
    method: InvitationMethod,
    accountId: string,
  ): Promise<SendInvitationsResponse> {
    return this.api.invoke(
      sendEventInvitations,
      { id: eventId, body: { method, accountId } },
      silent(),
    );
  }
}

/**
 * Sends invitations (iTIP REQUEST) or a cancellation (CANCEL) for an event of a local calendar to
 * its guests by mail, over a mail account the user picks (SPEC.md §6.2). Closes with what was sent.
 */
@Component({
  selector: 'app-calendar-invitation-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatRadioModule,
    MatSelectModule,
  ],
  template: `
    <h2 mat-dialog-title i18n="@@calendar.invite.title">Send invitations</h2>
    <mat-dialog-content>
      <p class="event-title">{{ data.title }}</p>
      <p class="note" id="calendar-invite-guests">
        <ng-container i18n="@@calendar.invite.to">To:</ng-container>
        {{ data.attendees.join(', ') }}
      </p>

      <mat-radio-group
        class="methods"
        [formControl]="method"
        aria-labelledby="calendar-invite-method"
      >
        <span id="calendar-invite-method" class="visually-hidden" i18n="@@calendar.invite.method"
          >What to send</span
        >
        <mat-radio-button value="REQUEST" i18n="@@calendar.invite.request"
          >Invitation (or update)</mat-radio-button
        >
        <mat-radio-button value="CANCEL" i18n="@@calendar.invite.cancel"
          >Cancellation</mat-radio-button
        >
      </mat-radio-group>

      @if (loading()) {
        <p class="note" role="status" i18n="@@calendar.invite.loading">Loading mail accounts…</p>
      } @else if (accounts().length === 0) {
        <p class="error" role="alert" i18n="@@calendar.invite.noAccounts">
          Add a mail account in the settings to send invitations.
        </p>
      } @else {
        <mat-form-field class="wide" subscriptSizing="dynamic">
          <mat-label i18n="@@calendar.invite.account">Send from</mat-label>
          <mat-select [formControl]="accountId">
            @for (account of accounts(); track account.id) {
              <mat-option [value]="account.id"
                >{{ account.displayName }} ({{ account.emailAddress }})</mat-option
              >
            }
          </mat-select>
        </mat-form-field>
      }

      @if (error(); as message) {
        <p class="error" role="alert">{{ message }}</p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button
        mat-button
        type="button"
        [mat-dialog-close]="undefined"
        [disabled]="sending()"
        i18n="@@calendar.cancel"
      >
        Cancel
      </button>
      <button
        mat-flat-button
        type="button"
        [disabled]="sending() || loading() || accountId.invalid"
        (click)="send()"
        i18n="@@calendar.invite.send"
      >
        Send
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .event-title {
      margin: 0 0 var(--app-space-1);
      font-weight: 500;
    }
    .note {
      margin: 0 0 var(--app-space-3);
      color: var(--mat-sys-on-surface-variant);
      overflow-wrap: anywhere;
    }
    .methods {
      display: flex;
      flex-wrap: wrap;
      gap: var(--app-space-2);
      margin-bottom: var(--app-space-3);
    }
    .wide {
      width: 100%;
    }
    .error {
      color: var(--mat-sys-error);
    }
  `,
})
export class InvitationDialog implements OnInit {
  protected readonly data = inject<InvitationDialogData>(MAT_DIALOG_DATA);
  private readonly ref =
    inject<MatDialogRef<InvitationDialog, SendInvitationsResponse>>(MatDialogRef);
  private readonly invitations = inject(InvitationService);

  protected readonly method = new FormControl<InvitationMethod>('REQUEST', { nonNullable: true });
  protected readonly accountId = new FormControl('', {
    nonNullable: true,
    validators: [Validators.required],
  });
  protected readonly accounts = signal<readonly MailAccountResponse[]>([]);
  protected readonly loading = signal(true);
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);

  async ngOnInit(): Promise<void> {
    try {
      const accounts = await this.invitations.accounts();
      this.accounts.set(accounts);
      if (accounts.length) this.accountId.setValue(accounts[0].id);
    } catch (error) {
      this.error.set(
        error instanceof ApiError
          ? error.userMessage
          : $localize`:@@calendar.invite.accountsFailed:The mail accounts could not be loaded.`,
      );
    } finally {
      this.loading.set(false);
    }
  }

  protected async send(): Promise<void> {
    if (this.sending() || this.accountId.invalid) return;
    this.sending.set(true);
    this.error.set(null);
    // The mail may go out even if its answer never arrives here: no closing until it has.
    this.ref.disableClose = true;
    try {
      this.ref.close(
        await this.invitations.send(this.data.eventId, this.method.value, this.accountId.value),
      );
    } catch (error) {
      this.error.set(failure(error));
    } finally {
      this.ref.disableClose = false;
      this.sending.set(false);
    }
  }
}

/** What to tell the user about a failed send. */
function failure(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return $localize`:@@calendar.invite.failed:The invitations could not be sent.`;
  }
  if (error.status === 422) {
    return $localize`:@@calendar.invite.rejected:The mail server refused the message. Check the guests' addresses.`;
  }
  return error.errors[0]?.message ?? error.userMessage;
}

/** Opens the {@link InvitationDialog}. */
@Injectable({ providedIn: 'root' })
export class InvitationPrompt {
  private readonly dialog = inject(MatDialog);

  /** What was sent, `undefined` when the user cancels. */
  open(data: InvitationDialogData): Promise<SendInvitationsResponse | undefined> {
    const ref = this.dialog.open<InvitationDialog, InvitationDialogData, SendInvitationsResponse>(
      InvitationDialog,
      { data, autoFocus: 'first-tabbable', width: '28rem', maxWidth: '95vw' },
    );
    return firstValueFrom(ref.afterClosed());
  }
}

function silent(): HttpContext {
  return new HttpContext().set(SILENT_ERRORS, true);
}
