import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { filter } from 'rxjs';
import {
  ContactRelatedResponse,
  ContactRequest,
  ContactResponse,
  ItemRefDto,
  OAuthConnectionResponse,
} from '../../api/generated';
import { ChangeEventsService } from '../../core/events';
import { ApiError, NotificationService } from '../../core/http';
import { LinkedItems } from '../../shared/links';
import { renderMarkdown } from '../gtd/projects/markdown';
import {
  addressLine,
  eventLink,
  initials,
  isAllDay,
  roleLine,
  sourceLabel,
  syncProvider,
  telUrl,
} from './contact-display';
import { ContactForm } from './contact-form';
import { ContactsService } from './contacts.service';

/** The route id of a contact being created. */
export const NEW_CONTACT_ID = 'new';

/**
 * One contact (`/contacts/:id`, SPEC.md §8.3): its fields, where it syncs from, the Waiting For
 * items naming it, upcoming events and recent mails with it, and its linked items. Edit, export as
 * vCard or move it to the Trash. `/contacts/new` creates a contact.
 */
@Component({
  selector: 'app-contact-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  imports: [ContactForm, DatePipe, LinkedItems, MatButtonModule, RouterLink],
  templateUrl: './contact-detail.html',
  styleUrl: './contact-detail.scss',
})
export default class ContactDetail {
  private readonly service = inject(ContactsService);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  /** The route's `:id`; `new` while creating. */
  protected readonly id = signal('');
  protected readonly isNew = computed(() => this.id() === NEW_CONTACT_ID);

  protected readonly contact = signal<ContactResponse | null>(null);
  protected readonly loadError = signal<string | null>(null);
  protected readonly related = signal<ContactRelatedResponse | null>(null);
  protected readonly relatedError = signal<string | null>(null);
  /** The account the contact syncs with, when it comes from one. */
  protected readonly account = signal<OAuthConnectionResponse | null>(null);

  protected readonly editing = signal(false);
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);
  protected readonly deleting = signal(false);

  protected readonly ref = computed<ItemRefDto>(() => ({ type: 'CONTACT', id: this.id() }));
  protected readonly notesHtml = computed(() => {
    const notes = this.contact()?.notesMd;
    return notes ? renderMarkdown(notes) : null;
  });
  protected readonly exportUrl = computed(() => this.service.exportUrl([this.id()]));
  /** The list's search, kept on the way back. */
  protected readonly backParams = signal<{ q?: string }>({});

  protected readonly initials = initials;
  protected readonly roleLine = roleLine;
  protected readonly sourceLabel = sourceLabel;
  protected readonly addressLine = addressLine;
  protected readonly telUrl = telUrl;
  protected readonly isAllDay = isAllDay;
  protected readonly eventLink = eventLink;

  /** Counts the loads: a contact that arrives for an earlier id is ignored. */
  private loadSeq = 0;

  constructor() {
    const destroyRef = inject(DestroyRef);
    this.route.paramMap.pipe(takeUntilDestroyed(destroyRef)).subscribe((params) => {
      this.id.set(params.get('id') ?? '');
      void this.load();
    });
    this.route.queryParamMap.pipe(takeUntilDestroyed(destroyRef)).subscribe((params) => {
      const q = params.get('q');
      this.backParams.set(q ? { q } : {});
    });
    const events = inject(ChangeEventsService);
    events
      .events$('CONTACT')
      .pipe(
        filter((event) => event.id === this.id()),
        takeUntilDestroyed(destroyRef),
      )
      .subscribe((event) => void this.changedElsewhere(event.op));
  }

  protected async load(): Promise<void> {
    const seq = ++this.loadSeq;
    this.loadError.set(null);
    this.contact.set(null);
    this.related.set(null);
    this.relatedError.set(null);
    this.account.set(null);
    this.saveError.set(null);
    this.editing.set(this.isNew());
    if (this.isNew()) return;
    try {
      const contact = await this.service.get(this.id());
      if (seq !== this.loadSeq) return;
      this.contact.set(contact);
      void this.loadRelated(seq);
      void this.loadAccount(seq, contact);
    } catch (error) {
      if (seq === this.loadSeq) this.loadError.set(message(error));
    }
  }

  protected async loadRelated(seq = this.loadSeq): Promise<void> {
    this.relatedError.set(null);
    try {
      const related = await this.service.related(this.id());
      if (seq === this.loadSeq) this.related.set(related);
    } catch (error) {
      if (seq === this.loadSeq) this.relatedError.set(message(error));
    }
  }

  protected edit(): void {
    this.saveError.set(null);
    this.editing.set(true);
  }

  protected cancelEdit(): void {
    if (this.isNew()) {
      void this.router.navigate(['/contacts'], { queryParams: this.backParams() });
      return;
    }
    this.saveError.set(null);
    this.editing.set(false);
  }

  protected async save(request: ContactRequest): Promise<void> {
    if (this.saving()) return;
    this.saving.set(true);
    this.saveError.set(null);
    try {
      if (this.isNew()) {
        const created = await this.service.create(request);
        this.notifications.info(
          $localize`:@@contacts.created:Contact “${created.displayName}:name:” created`,
        );
        await this.router.navigate(['/contacts', created.id], { replaceUrl: true });
        return;
      }
      const contact = this.contact();
      if (!contact) return;
      this.contact.set(await this.service.update(contact, request));
      this.editing.set(false);
      void this.loadRelated();
    } catch (error) {
      if (isConflict(error)) {
        // The form keeps the user's edits; the next Save sends them against the current version.
        this.saveError.set(
          $localize`:@@contacts.saveConflict:it was changed elsewhere in the meantime. Your changes are kept: Save again to replace that version, or Cancel to see it.`,
        );
        await this.reloadContact();
      } else {
        this.saveError.set(message(error));
      }
    } finally {
      this.saving.set(false);
    }
  }

  protected async remove(): Promise<void> {
    const contact = this.contact();
    if (!contact || this.deleting()) return;
    this.deleting.set(true);
    try {
      await this.service.remove(contact);
      this.notifications.info(
        $localize`:@@contacts.trashed:Moved “${contact.displayName}:name:” to the Trash`,
      );
      await this.router.navigate(['/contacts'], { queryParams: this.backParams() });
    } catch (error) {
      this.notifications.error(message(error));
      if (isConflict(error)) await this.reloadContact();
    } finally {
      this.deleting.set(false);
    }
  }

  /** After a conflict: the current version, so the next try sends its version as If-Match. */
  private async reloadContact(): Promise<void> {
    try {
      this.contact.set(await this.service.get(this.id()));
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) this.loadError.set(message(error));
    }
  }

  /** A change from elsewhere (another device, a sync): shown unless it is being edited here. */
  private async changedElsewhere(op: string): Promise<void> {
    if (!this.contact() || this.saving() || this.deleting()) return;
    if (op === 'DELETED') {
      this.loadError.set($localize`:@@contacts.gone:This contact was moved to the Trash.`);
      return;
    }
    if (this.editing()) return;
    const seq = this.loadSeq;
    try {
      const contact = await this.service.get(this.id());
      if (seq !== this.loadSeq || this.editing()) return;
      if (contact.version > (this.contact()?.version ?? 0)) {
        this.contact.set(contact);
        void this.loadRelated(seq);
      }
    } catch {
      // The contact stays; the next action shows the problem.
    }
  }

  private async loadAccount(seq: number, contact: ContactResponse): Promise<void> {
    const provider = syncProvider(contact.source);
    if (!provider) return;
    try {
      const accounts = await this.service.syncAccounts();
      if (seq !== this.loadSeq) return;
      // Sync links are not exposed, so with several accounts of the provider the first one is shown.
      this.account.set(accounts.find((a) => a.provider === provider) ?? null);
    } catch {
      // Only the sync note is missing then.
    }
  }
}

function isConflict(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 409 || error.status === 412);
}

function message(error: unknown): string {
  return error instanceof ApiError
    ? error.userMessage
    : $localize`:@@contacts.error:Something went wrong.`;
}
