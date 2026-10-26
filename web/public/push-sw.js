// Web Push service worker (SPEC.md §4.6): shows reminders and new mail while no My Cockpit tab is
// open. Registered by the web app (src/app/core/notifications/browser-push.ts) as
// `push-sw.js?lang=<locale>` next to its index.html, so each locale build has its own worker and
// scope. The server sends each `reminder` and `mail` SSE event as a push message
// `{"event": <name>, "data": <event data>}`; `data` is missing when it was too large for one message.
'use strict';

const TEXTS = {
  en: {
    reminder: 'Reminder',
    reminderBody: 'Reminder from My Cockpit',
    newMail: 'New mail',
    newMessages: (count) => `${count} new messages`,
  },
  de: {
    reminder: 'Erinnerung',
    reminderBody: 'Erinnerung von My Cockpit',
    newMail: 'Neue E-Mail',
    newMessages: (count) => `${count} neue Nachrichten`,
  },
};

const LANG = (new URL(self.location.href).searchParams.get('lang') || 'en').split('-')[0];
const T = TEXTS[LANG] || TEXTS.en;

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()));

self.addEventListener('push', (event) => event.waitUntil(onPush(event)));
self.addEventListener('notificationclick', (event) => event.waitUntil(onClick(event)));

async function onPush(event) {
  const notification = pushNotification(readMessage(event));
  if (!notification) return;
  // A visible tab already shows it (from its SSE stream), so don't show it twice.
  const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
  if (windows.some((client) => client.visibilityState === 'visible')) return;
  await self.registration.showNotification(notification.title, notification.options);
}

function readMessage(event) {
  try {
    return (event.data && event.data.json()) || {};
  } catch {
    return {};
  }
}

/** The notification for a push message, or null for an unknown event. `data.route` is an app route. */
function pushNotification(message) {
  const data = message.data;
  const icon = appUrl('apple-touch-icon.png');
  if (message.event === 'reminder') {
    const item = (data && data.item) || {};
    return {
      title: item.title || T.reminder,
      options: {
        body: T.reminderBody,
        icon,
        // The same tag as the in-page notification, so an open but hidden tab doesn't add a second one.
        tag: data && data.id ? `reminder-${data.id}` : 'reminder',
        data: { route: item.available && item.deepLink ? item.deepLink : '' },
      },
    };
  }
  if (message.event === 'mail') {
    const messages = (data && data.messages) || [];
    const count = (data && data.count) || messages.length;
    const single = count === 1 && messages.length === 1 ? messages[0] : undefined;
    return {
      title: single ? single.from || T.newMail : count > 1 ? T.newMessages(count) : T.newMail,
      options: {
        body: single
          ? single.subject || ''
          : messages
              .map((m) => m.subject)
              .filter(Boolean)
              .join('\n'),
        icon,
        tag: data && data.folderId ? `mail-${data.folderId}` : 'mail',
        data: { route: single ? `mail/messages/${single.id}` : 'mail' },
      },
    };
  }
  return null;
}

/** An app route or file as an absolute URL inside this worker's scope (the locale build's base). */
function appUrl(route) {
  const url = new URL(String(route || '').replace(/^\/+/, ''), self.registration.scope);
  // Only ever open pages of the app itself.
  return url.href.startsWith(self.registration.scope) ? url.href : self.registration.scope;
}

/** Opens the notification's item: in an open My Cockpit tab if there is one, else in a new one. */
async function onClick(event) {
  event.notification.close();
  const url = appUrl(event.notification.data && event.notification.data.route);
  const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
  const open = windows.find((client) => client.url.startsWith(self.registration.scope));
  if (open) {
    try {
      // Focus first: browsers allow it only shortly after the click. navigate() only works for
      // tabs this worker controls.
      await open.focus();
      await open.navigate(url);
      return;
    } catch {
      // Fall through to a new tab.
    }
  }
  await self.clients.openWindow(url);
}
