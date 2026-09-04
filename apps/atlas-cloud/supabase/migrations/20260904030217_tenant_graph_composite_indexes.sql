-- Composite foreign keys enforce same-tenant edges and need indexes in FK column order.
create index atlas_stations_site_org_fk on public.atlas_stations(site_id,organization_id);
create index atlas_devices_station_org_fk on public.atlas_registered_devices(station_id,organization_id);
create index atlas_tasks_site_org_fk on public.atlas_task_runs(site_id,organization_id);
create index atlas_tasks_station_org_fk on public.atlas_task_runs(station_id,organization_id);
create index atlas_tasks_procedure_org_fk on public.atlas_task_runs(procedure_revision_id,organization_id);
create index atlas_sessions_task_org_fk on public.atlas_runtime_sessions(task_run_id,organization_id);
create index atlas_sessions_device_org_fk on public.atlas_runtime_sessions(device_id,organization_id);
create index atlas_sessions_site_org_fk on public.atlas_runtime_sessions(site_id,organization_id);
create index atlas_sessions_station_org_fk on public.atlas_runtime_sessions(station_id,organization_id);
