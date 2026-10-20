# Atlas Android closed-alpha guide

This alpha tests whether Atlas feels like a present, persistent partner during a real physical task. It is early software, not an emergency service or professional safety authority.

## Install the right package

- Use **secure** for HTTPS cloud or HTTPS LAN inference. This is the default and safest build.
- Use **trusted-LAN** only when your inference server is on a private network and does not support HTTPS. HTTP traffic can be observed or changed by others on that network.
- The trusted-LAN package installs as **Atlas LAN** with a separate application ID and private data store so it cannot silently weaken or inherit the secure package.
- Verify the published SHA-256 checksum and signing-certificate fingerprint before sideloading.

## First session

1. Read the first-run explanation and grant camera/microphone permissions. Notification access keeps the active physical session visible.
2. In **Inference**, enter an OpenAI-compatible base URL, model, and optional key. Atlas appends the chat-completions path as documented in the Android README.
3. Select only capabilities the endpoint really supports, then tap **Test connection**. Saving remains disabled until the test succeeds.
4. Return to **Session**, describe one concrete goal, and start.
5. Tap **Talk**, wait for the haptic/chirp and “Speak now,” then speak normally. Use **Observe now** when visual detail matters.
6. Turn on **Live Context** only when you want rolling capture/review. Pause or end immediately stops it.

## Trust and recovery

- **Data** shows the local event trace and image-retention setting.
- **Export** creates a ZIP with a readable transcript and machine-readable state. Review it before sending it to anyone.
- **Delete** permanently removes the current session and its Atlas-owned images from the phone.
- Removing an inference endpoint deletes its encrypted credential. Uninstalling Atlas removes all app-private data.
- If a provider fails, preserve the session, check the endpoint from **Inference**, and retry. Atlas does not silently replay an ambiguous physical tool action.

## Report useful evidence

Include app version/build variant, phone model, Android version, inference implementation, whether earbuds were used, exact reproduction steps, and the event types around the failure. Do not send an export unless requested through the private support channel and you have reviewed its contents.

Immediately stop testing and report privately if you see data from another user/workspace, a secret in UI/logs, a tool execute without confirmation, Live Context continue after pause/end, deletion leave accessible data, or uncontrolled repeated inference/action.

## Shared controls (alpha.17)

Session and Workspaces use one anchored dock above bottom navigation: Observe, central PTT, text entry, Pause/Resume and End. The dock remains in place while the transcript or workspace scrolls; text drafts persist between those two tabs. System keeps its settings layout. Model replies and streaming text remain in Session chat, not a sticky workspace footer.

A slow amber PTT glow and separate accessible warning icon indicate a pending clarification, approval, or stopped task requiring a decision. The PTT action stays voice input. Tap the warning icon to read/respond to pending actions from either tab; closing the panel does not clear the blocker. Provider failures/retries alone do not trigger the user-action glow. The indicator clears when Core reports the blocker resolved.

Phone QA: check long transcripts, long workspaces, keyboard open/close, large font sizes, landscape, draft preservation across tabs, clarification answers by voice/text, approval/decline, paused sessions, and glow clearing after resolution.
