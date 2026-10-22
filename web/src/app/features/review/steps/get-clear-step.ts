import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { InboxPage } from '../../gtd/inbox/inbox-page';

/**
 * Step 1: the Inbox with its processing mode, embedded (SPEC.md §5.3), and a pointer to the
 * flagged mails to capture from.
 */
@Component({
  selector: 'app-review-get-clear-step',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [InboxPage, RouterLink],
  styleUrl: './steps.scss',
  template: `
    <app-inbox-page class="embedded" />
    <p class="aside" i18n="@@review.getClear.mail">
      Flagged mails waiting for an action? Open <a routerLink="/mail">Mail</a> and turn them into
      tasks, then come back.
    </p>
  `,
})
export class GetClearStep {}
