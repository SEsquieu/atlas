-- Cover tenant graph foreign keys used by deletes, joins, and audit lookups.
create index atlas_sites_organization on public.atlas_sites(organization_id);
create index atlas_stations_site_fk on public.atlas_stations(site_id);
create index atlas_devices_station_fk on public.atlas_registered_devices(station_id);
create index atlas_procedures_created_by on public.atlas_procedure_revisions(created_by);
create index atlas_tasks_procedure_fk on public.atlas_task_runs(procedure_revision_id);
create index atlas_tasks_site_fk on public.atlas_task_runs(site_id);
create index atlas_tasks_station_fk on public.atlas_task_runs(station_id);
create index atlas_tasks_assignee_fk on public.atlas_task_runs(assigned_principal_id);
create index atlas_sessions_task_fk on public.atlas_runtime_sessions(task_run_id);
create index atlas_sessions_actor_fk on public.atlas_runtime_sessions(actor_user_id);
create index atlas_sessions_device_fk on public.atlas_runtime_sessions(device_id);
create index atlas_sessions_site_fk on public.atlas_runtime_sessions(site_id);
create index atlas_sessions_station_fk on public.atlas_runtime_sessions(station_id);
create index atlas_policies_created_by on public.atlas_runtime_policies(created_by);
create index atlas_events_session_fk on public.atlas_runtime_events(session_id);
create index atlas_events_task_fk on public.atlas_runtime_events(task_run_id);
create index atlas_events_actor_fk on public.atlas_runtime_events(actor_user_id);
create index atlas_events_device_fk on public.atlas_runtime_events(device_id);
create index atlas_events_site_fk on public.atlas_runtime_events(site_id);
create index atlas_events_station_fk on public.atlas_runtime_events(station_id);
create index atlas_events_procedure_fk on public.atlas_runtime_events(procedure_revision_id);
create index atlas_org_ledger_org_created on public.atlas_organization_credit_ledger(organization_id,created_at desc);
create index atlas_org_ledger_actor on public.atlas_organization_credit_ledger(actor_user_id);
create index atlas_org_usage_user on public.atlas_organization_inference_usage(user_id);

-- Legacy user-keyed billing remains temporarily for rollback compatibility and is service-role only.
-- Explicit deny policies make that posture visible to schema tooling.
create policy atlas_legacy_billing_deny on public.atlas_billing_accounts for select to authenticated using (false);
create policy atlas_legacy_ledger_deny on public.atlas_credit_ledger for select to authenticated using (false);
create policy atlas_legacy_usage_deny on public.atlas_inference_usage for select to authenticated using (false);
create policy atlas_stripe_events_deny on public.atlas_stripe_events for select to authenticated using (false);
