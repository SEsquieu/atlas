-- Atlas tenant boundary. Personal accounts are represented as one-member organizations so
-- consumer and enterprise deployments share the same ownership and accounting paths.
create table public.atlas_organizations (
  id uuid primary key default gen_random_uuid(),
  kind text not null check (kind in ('personal','organization')),
  name text not null,
  created_by uuid not null references auth.users(id) on delete restrict,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create unique index atlas_one_personal_org_per_user on public.atlas_organizations(created_by) where kind = 'personal';

create table public.atlas_organization_memberships (
  organization_id uuid not null references public.atlas_organizations(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  role text not null check (role in ('owner','admin','operator','viewer','billing')),
  status text not null default 'active' check (status in ('invited','active','suspended')),
  created_at timestamptz not null default now(),
  primary key (organization_id,user_id)
);
create index atlas_memberships_user on public.atlas_organization_memberships(user_id,organization_id) where status = 'active';

create table public.atlas_sites (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.atlas_organizations(id) on delete cascade,
  name text not null,
  metadata jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create table public.atlas_stations (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.atlas_organizations(id) on delete cascade,
  site_id uuid references public.atlas_sites(id) on delete set null,
  name text not null,
  station_type text not null default 'mobile',
  configuration jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index atlas_stations_site on public.atlas_stations(organization_id,site_id);

create table public.atlas_registered_devices (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.atlas_organizations(id) on delete cascade,
  station_id uuid references public.atlas_stations(id) on delete set null,
  external_device_id text not null,
  name text,
  platform text not null,
  capabilities jsonb not null default '[]'::jsonb,
  status text not null default 'active' check (status in ('active','disabled','retired')),
  last_seen_at timestamptz,
  created_at timestamptz not null default now(),
  unique (organization_id,external_device_id)
);

create table public.atlas_procedure_revisions (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.atlas_organizations(id) on delete cascade,
  procedure_key text not null,
  version text not null,
  status text not null default 'draft' check (status in ('draft','published','retired')),
  definition jsonb not null,
  created_by uuid not null references auth.users(id) on delete restrict,
  created_at timestamptz not null default now(),
  published_at timestamptz,
  unique (organization_id,procedure_key,version)
);

create table public.atlas_task_runs (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.atlas_organizations(id) on delete cascade,
  site_id uuid references public.atlas_sites(id) on delete set null,
  station_id uuid references public.atlas_stations(id) on delete set null,
  procedure_revision_id uuid references public.atlas_procedure_revisions(id) on delete restrict,
  assigned_principal_id uuid references auth.users(id) on delete set null,
  external_ref text,
  goal text,
  status text not null default 'pending' check (status in ('pending','active','blocked','completed','cancelled')),
  current_step_id text,
  state jsonb not null default '{}'::jsonb,
  started_at timestamptz,
  completed_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index atlas_task_runs_active on public.atlas_task_runs(organization_id,station_id,status,updated_at desc);

create table public.atlas_runtime_sessions (
  id uuid primary key,
  organization_id uuid not null references public.atlas_organizations(id) on delete cascade,
  task_run_id uuid references public.atlas_task_runs(id) on delete set null,
  actor_user_id uuid references auth.users(id) on delete set null,
  device_id uuid references public.atlas_registered_devices(id) on delete set null,
  site_id uuid references public.atlas_sites(id) on delete set null,
  station_id uuid references public.atlas_stations(id) on delete set null,
  status text not null check (status in ('idle','active','paused','done','error')),
  mode text not null check (mode in ('manual','assisted','ambient')),
  goal text,
  state jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index atlas_runtime_sessions_org_updated on public.atlas_runtime_sessions(organization_id,updated_at desc);

create table public.atlas_runtime_policies (
  id uuid not null default gen_random_uuid(),
  revision integer not null check (revision > 0),
  organization_id uuid not null references public.atlas_organizations(id) on delete cascade,
  scope text not null check (scope in ('organization','site','station','procedure','assignment','run')),
  scope_id uuid not null,
  policy jsonb not null,
  created_by uuid not null references auth.users(id) on delete restrict,
  created_at timestamptz not null default now(),
  primary key (id,revision)
);
create index atlas_runtime_policies_scope on public.atlas_runtime_policies(organization_id,scope,scope_id,revision desc);

create table public.atlas_scoped_memory (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.atlas_organizations(id) on delete cascade,
  scope text not null check (scope in ('session','task','principal','workspace','environment')),
  scope_id text not null,
  kind text not null,
  content text not null,
  confidence double precision check (confidence between 0 and 1),
  source_event_id uuid,
  expires_at timestamptz,
  metadata jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index atlas_scoped_memory_lookup on public.atlas_scoped_memory(organization_id,scope,scope_id,updated_at desc);

create table public.atlas_runtime_events (
  id uuid primary key,
  organization_id uuid not null references public.atlas_organizations(id) on delete cascade,
  session_id uuid references public.atlas_runtime_sessions(id) on delete set null,
  task_run_id uuid references public.atlas_task_runs(id) on delete set null,
  actor_user_id uuid references auth.users(id) on delete set null,
  device_id uuid references public.atlas_registered_devices(id) on delete set null,
  site_id uuid references public.atlas_sites(id) on delete set null,
  station_id uuid references public.atlas_stations(id) on delete set null,
  procedure_revision_id uuid references public.atlas_procedure_revisions(id) on delete set null,
  event_type text not null,
  correlation_id uuid,
  causation_id uuid,
  data jsonb not null default '{}'::jsonb,
  occurred_at timestamptz not null,
  ingested_at timestamptz not null default now()
);
create index atlas_runtime_events_replay on public.atlas_runtime_events(organization_id,session_id,occurred_at,id);
create index atlas_runtime_events_task on public.atlas_runtime_events(organization_id,task_run_id,occurred_at,id);

create table public.atlas_organization_billing_accounts (
  organization_id uuid primary key references public.atlas_organizations(id) on delete cascade,
  balance_micros bigint not null default 0 check (balance_micros >= 0),
  stripe_customer_id text unique,
  stripe_subscription_id text unique,
  subscription_status text not null default 'none',
  subscription_period_end timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.atlas_organization_credit_ledger (
  id bigint generated always as identity primary key,
  organization_id uuid not null references public.atlas_organizations(id) on delete cascade,
  actor_user_id uuid references auth.users(id) on delete set null,
  amount_micros bigint not null,
  kind text not null,
  reference text not null unique,
  created_at timestamptz not null default now()
);

create table public.atlas_organization_inference_usage (
  request_id uuid primary key,
  organization_id uuid not null references public.atlas_organizations(id) on delete cascade,
  user_id uuid references auth.users(id) on delete set null,
  session_id uuid,
  task_run_id uuid,
  capability text,
  reserved_micros bigint not null,
  charged_micros bigint,
  model text,
  input_tokens integer,
  output_tokens integer,
  latency_ms integer,
  status text not null,
  created_at timestamptz not null default now(),
  settled_at timestamptz
);
create index atlas_org_usage_created on public.atlas_organization_inference_usage(organization_id,created_at desc);

-- Prevent cross-tenant graph edges even if a caller knows another tenant's UUID.
alter table public.atlas_sites add constraint atlas_sites_id_org_unique unique (id,organization_id);
alter table public.atlas_stations add constraint atlas_stations_id_org_unique unique (id,organization_id);
alter table public.atlas_registered_devices add constraint atlas_devices_id_org_unique unique (id,organization_id);
alter table public.atlas_procedure_revisions add constraint atlas_procedures_id_org_unique unique (id,organization_id);
alter table public.atlas_task_runs add constraint atlas_task_runs_id_org_unique unique (id,organization_id);
alter table public.atlas_runtime_sessions add constraint atlas_sessions_id_org_unique unique (id,organization_id);
alter table public.atlas_stations add constraint atlas_stations_site_same_org foreign key (site_id,organization_id) references public.atlas_sites(id,organization_id);
alter table public.atlas_registered_devices add constraint atlas_devices_station_same_org foreign key (station_id,organization_id) references public.atlas_stations(id,organization_id);
alter table public.atlas_task_runs add constraint atlas_tasks_site_same_org foreign key (site_id,organization_id) references public.atlas_sites(id,organization_id);
alter table public.atlas_task_runs add constraint atlas_tasks_station_same_org foreign key (station_id,organization_id) references public.atlas_stations(id,organization_id);
alter table public.atlas_task_runs add constraint atlas_tasks_procedure_same_org foreign key (procedure_revision_id,organization_id) references public.atlas_procedure_revisions(id,organization_id);
alter table public.atlas_runtime_sessions add constraint atlas_sessions_task_same_org foreign key (task_run_id,organization_id) references public.atlas_task_runs(id,organization_id);
alter table public.atlas_runtime_sessions add constraint atlas_sessions_device_same_org foreign key (device_id,organization_id) references public.atlas_registered_devices(id,organization_id);
alter table public.atlas_runtime_sessions add constraint atlas_sessions_site_same_org foreign key (site_id,organization_id) references public.atlas_sites(id,organization_id);
alter table public.atlas_runtime_sessions add constraint atlas_sessions_station_same_org foreign key (station_id,organization_id) references public.atlas_stations(id,organization_id);

create or replace function public.atlas_ensure_personal_organization(p_user_id uuid)
returns uuid language plpgsql security definer set search_path = '' as $$
declare org_id uuid;
begin
  if p_user_id is null then raise exception 'user is required'; end if;
  select id into org_id from public.atlas_organizations where kind='personal' and created_by=p_user_id;
  if org_id is null then
    insert into public.atlas_organizations(kind,name,created_by) values ('personal','Personal',p_user_id)
      on conflict (created_by) where kind='personal' do update set updated_at=now()
      returning id into org_id;
  end if;
  insert into public.atlas_organization_memberships(organization_id,user_id,role,status)
    values (org_id,p_user_id,'owner','active') on conflict (organization_id,user_id) do update set status='active';
  insert into public.atlas_organization_billing_accounts(organization_id) values (org_id) on conflict do nothing;
  return org_id;
end; $$;

create or replace function public.atlas_resolve_organization(p_user_id uuid,p_requested_organization_id uuid default null)
returns uuid language plpgsql security definer set search_path = '' as $$
declare org_id uuid;
begin
  if p_requested_organization_id is null then return public.atlas_ensure_personal_organization(p_user_id); end if;
  select organization_id into org_id from public.atlas_organization_memberships
    where organization_id=p_requested_organization_id and user_id=p_user_id and status='active';
  if org_id is null then raise exception 'user is not an active organization member' using errcode='42501'; end if;
  return org_id;
end; $$;

create or replace function public.atlas_reserve_organization_credits(
  p_organization_id uuid,p_user_id uuid,p_request_id uuid,p_amount bigint,p_session_id uuid default null,
  p_task_run_id uuid default null,p_capability text default null
) returns boolean language plpgsql security definer set search_path = '' as $$
declare available bigint;
begin
  if p_amount <= 0 then raise exception 'reservation must be positive'; end if;
  if not exists (select 1 from public.atlas_organization_memberships where organization_id=p_organization_id and user_id=p_user_id and status='active')
    then raise exception 'user is not an active organization member' using errcode='42501'; end if;
  insert into public.atlas_organization_billing_accounts(organization_id) values (p_organization_id) on conflict do nothing;
  select balance_micros into available from public.atlas_organization_billing_accounts where organization_id=p_organization_id for update;
  if available < p_amount then return false; end if;
  update public.atlas_organization_billing_accounts set balance_micros=balance_micros-p_amount,updated_at=now() where organization_id=p_organization_id;
  insert into public.atlas_organization_inference_usage(request_id,organization_id,user_id,session_id,task_run_id,capability,reserved_micros,status)
    values (p_request_id,p_organization_id,p_user_id,p_session_id,p_task_run_id,p_capability,p_amount,'reserved');
  insert into public.atlas_organization_credit_ledger(organization_id,actor_user_id,amount_micros,kind,reference)
    values (p_organization_id,p_user_id,-p_amount,'inference_reservation','reserve:'||p_request_id);
  return true;
end; $$;

create or replace function public.atlas_settle_organization_credits(
  p_organization_id uuid,p_user_id uuid,p_request_id uuid,p_reserved bigint,p_actual bigint,p_model text,
  p_input_tokens integer,p_output_tokens integer,p_latency_ms integer,p_status text
) returns void language plpgsql security definer set search_path = '' as $$
declare refund bigint;
begin
  p_actual := greatest(0,least(p_actual,p_reserved));
  update public.atlas_organization_inference_usage set charged_micros=p_actual,model=p_model,input_tokens=p_input_tokens,
    output_tokens=p_output_tokens,latency_ms=p_latency_ms,status=p_status,settled_at=now()
    where request_id=p_request_id and organization_id=p_organization_id and user_id=p_user_id and status='reserved';
  if not found then return; end if;
  refund := p_reserved-p_actual;
  if refund > 0 then
    update public.atlas_organization_billing_accounts set balance_micros=balance_micros+refund,updated_at=now() where organization_id=p_organization_id;
    insert into public.atlas_organization_credit_ledger(organization_id,actor_user_id,amount_micros,kind,reference)
      values (p_organization_id,p_user_id,refund,'inference_settlement','settle:'||p_request_id);
  end if;
end; $$;

create or replace function public.atlas_grant_organization_credits(p_organization_id uuid,p_amount bigint,p_reference text,p_kind text)
returns void language plpgsql security definer set search_path = '' as $$
begin
  if p_amount <= 0 then raise exception 'grant must be positive'; end if;
  insert into public.atlas_organization_credit_ledger(organization_id,amount_micros,kind,reference)
    values (p_organization_id,p_amount,p_kind,p_reference) on conflict (reference) do nothing;
  if found then
    insert into public.atlas_organization_billing_accounts(organization_id,balance_micros) values (p_organization_id,p_amount)
      on conflict (organization_id) do update set balance_micros=public.atlas_organization_billing_accounts.balance_micros+p_amount,updated_at=now();
  end if;
end; $$;

alter table public.atlas_organizations enable row level security;
alter table public.atlas_organization_memberships enable row level security;
alter table public.atlas_sites enable row level security;
alter table public.atlas_stations enable row level security;
alter table public.atlas_registered_devices enable row level security;
alter table public.atlas_procedure_revisions enable row level security;
alter table public.atlas_task_runs enable row level security;
alter table public.atlas_runtime_sessions enable row level security;
alter table public.atlas_runtime_policies enable row level security;
alter table public.atlas_scoped_memory enable row level security;
alter table public.atlas_runtime_events enable row level security;
alter table public.atlas_organization_billing_accounts enable row level security;
alter table public.atlas_organization_credit_ledger enable row level security;
alter table public.atlas_organization_inference_usage enable row level security;

-- The mobile client currently uses Atlas Cloud routes, not direct table access. Keep the Data API closed;
-- these membership policies provide defense in depth when selected tables are exposed later.
create schema if not exists private;
create or replace function private.atlas_is_organization_member(p_organization_id uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.atlas_organization_memberships
    where organization_id=p_organization_id and user_id=(select auth.uid()) and status='active'
  );
$$;
revoke all on function private.atlas_is_organization_member(uuid) from public,anon;
grant usage on schema private to authenticated;
grant execute on function private.atlas_is_organization_member(uuid) to authenticated;

create policy atlas_organizations_member_select on public.atlas_organizations for select to authenticated
  using ((select private.atlas_is_organization_member(id)));
create policy atlas_memberships_member_select on public.atlas_organization_memberships for select to authenticated
  using ((select private.atlas_is_organization_member(organization_id)));
create policy atlas_sites_member_select on public.atlas_sites for select to authenticated using ((select private.atlas_is_organization_member(organization_id)));
create policy atlas_stations_member_select on public.atlas_stations for select to authenticated using ((select private.atlas_is_organization_member(organization_id)));
create policy atlas_devices_member_select on public.atlas_registered_devices for select to authenticated using ((select private.atlas_is_organization_member(organization_id)));
create policy atlas_procedures_member_select on public.atlas_procedure_revisions for select to authenticated using ((select private.atlas_is_organization_member(organization_id)));
create policy atlas_tasks_member_select on public.atlas_task_runs for select to authenticated using ((select private.atlas_is_organization_member(organization_id)));
create policy atlas_sessions_member_select on public.atlas_runtime_sessions for select to authenticated using ((select private.atlas_is_organization_member(organization_id)));
create policy atlas_policies_member_select on public.atlas_runtime_policies for select to authenticated using ((select private.atlas_is_organization_member(organization_id)));
create policy atlas_memory_member_select on public.atlas_scoped_memory for select to authenticated using ((select private.atlas_is_organization_member(organization_id)));
create policy atlas_events_member_select on public.atlas_runtime_events for select to authenticated using ((select private.atlas_is_organization_member(organization_id)));
create policy atlas_billing_member_select on public.atlas_organization_billing_accounts for select to authenticated using ((select private.atlas_is_organization_member(organization_id)));
create policy atlas_ledger_member_select on public.atlas_organization_credit_ledger for select to authenticated using ((select private.atlas_is_organization_member(organization_id)));
create policy atlas_usage_member_select on public.atlas_organization_inference_usage for select to authenticated using ((select private.atlas_is_organization_member(organization_id)));

revoke all on public.atlas_organizations,public.atlas_organization_memberships,public.atlas_sites,public.atlas_stations,
  public.atlas_registered_devices,public.atlas_procedure_revisions,public.atlas_task_runs,public.atlas_runtime_sessions,
  public.atlas_runtime_policies,public.atlas_scoped_memory,public.atlas_runtime_events,public.atlas_organization_billing_accounts,
  public.atlas_organization_credit_ledger,public.atlas_organization_inference_usage from anon,authenticated;
grant all on public.atlas_organizations,public.atlas_organization_memberships,public.atlas_sites,public.atlas_stations,
  public.atlas_registered_devices,public.atlas_procedure_revisions,public.atlas_task_runs,public.atlas_runtime_sessions,
  public.atlas_runtime_policies,public.atlas_scoped_memory,public.atlas_runtime_events,public.atlas_organization_billing_accounts,
  public.atlas_organization_credit_ledger,public.atlas_organization_inference_usage to service_role;
grant usage,select on all sequences in schema public to service_role;

revoke all on function public.atlas_ensure_personal_organization(uuid) from public,anon,authenticated;
revoke all on function public.atlas_resolve_organization(uuid,uuid) from public,anon,authenticated;
revoke all on function public.atlas_reserve_organization_credits(uuid,uuid,uuid,bigint,uuid,uuid,text) from public,anon,authenticated;
revoke all on function public.atlas_settle_organization_credits(uuid,uuid,uuid,bigint,bigint,text,integer,integer,integer,text) from public,anon,authenticated;
revoke all on function public.atlas_grant_organization_credits(uuid,bigint,text,text) from public,anon,authenticated;
grant execute on function public.atlas_ensure_personal_organization(uuid) to service_role;
grant execute on function public.atlas_resolve_organization(uuid,uuid) to service_role;
grant execute on function public.atlas_reserve_organization_credits(uuid,uuid,uuid,bigint,uuid,uuid,text) to service_role;
grant execute on function public.atlas_settle_organization_credits(uuid,uuid,uuid,bigint,bigint,text,integer,integer,integer,text) to service_role;
grant execute on function public.atlas_grant_organization_credits(uuid,bigint,text,text) to service_role;
