import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { InboxPage } from '../../gtd/inbox/inbox-page';
import { GetClearStep } from './get-clear-step';

// The inbox page has its own spec; here it is a stand-in.
@Component({ selector: 'app-inbox-page', template: 'inbox' })
class InboxStub {}

describe('GetClearStep', () => {
  it('embeds the inbox processing and links to Mail for flagged mails', async () => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    TestBed.overrideComponent(GetClearStep, {
      remove: { imports: [InboxPage] },
      add: { imports: [InboxStub] },
    });
    const fixture = TestBed.createComponent(GetClearStep);
    fixture.detectChanges();
    await fixture.whenStable();
    const element: HTMLElement = fixture.nativeElement;

    const inbox = element.querySelector('app-inbox-page');
    expect(inbox?.textContent).toBe('inbox');
    expect(inbox?.classList.contains('embedded')).toBe(true);
    const mail = element.querySelector('a');
    expect(mail?.textContent?.trim()).toBe('Mail');
    expect(mail?.getAttribute('href')).toBe('/mail');
  });
});
