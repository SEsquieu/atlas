create table public.atlas_billing_accounts (
  user_id uuid primary key references auth.users(id) on delete cascade,
  balance_micros bigint not null default 0 check (balance_micros >= 0),
  stripe_customer_id text unique,
  stripe_subscription_id text unique,
  subscription_status text not null default 'none',
  subscription_period_end timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.atlas_credit_ledger (
  id bigint generated always as identity primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  amount_micros bigint not null,
  kind text not null,
  reference text not null unique,
  created_at timestamptz not null default now()
);

create table public.atlas_inference_usage (
  request_id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
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

create table public.atlas_stripe_events (
  event_id text primary key,
  event_type text not null,
  processed_at timestamptz not null default now()
);

create index atlas_credit_ledger_user_id_idx on public.atlas_credit_ledger(user_id);
create index atlas_inference_usage_user_id_idx on public.atlas_inference_usage(user_id);

alter table public.atlas_billing_accounts enable row level security;
alter table public.atlas_credit_ledger enable row level security;
alter table public.atlas_inference_usage enable row level security;
alter table public.atlas_stripe_events enable row level security;

revoke all on public.atlas_billing_accounts, public.atlas_credit_ledger, public.atlas_inference_usage, public.atlas_stripe_events from anon, authenticated;
grant all on public.atlas_billing_accounts, public.atlas_credit_ledger, public.atlas_inference_usage, public.atlas_stripe_events to service_role;
grant usage, select on all sequences in schema public to service_role;

create or replace function public.atlas_reserve_credits(p_user_id uuid, p_request_id uuid, p_amount bigint)
returns boolean
language plpgsql
security invoker
set search_path = ''
as $$
declare available bigint;
begin
  if p_amount <= 0 then raise exception 'reservation must be positive'; end if;
  insert into public.atlas_billing_accounts(user_id) values (p_user_id) on conflict do nothing;
  select balance_micros into available from public.atlas_billing_accounts where user_id = p_user_id for update;
  if available < p_amount then return false; end if;
  update public.atlas_billing_accounts set balance_micros = balance_micros - p_amount, updated_at = now() where user_id = p_user_id;
  insert into public.atlas_inference_usage(request_id,user_id,reserved_micros,status) values (p_request_id,p_user_id,p_amount,'reserved');
  insert into public.atlas_credit_ledger(user_id,amount_micros,kind,reference) values (p_user_id,-p_amount,'inference_reservation','reserve:' || p_request_id);
  return true;
end;
$$;

create or replace function public.atlas_settle_credits(
  p_user_id uuid, p_request_id uuid, p_reserved bigint, p_actual bigint, p_model text,
  p_input_tokens integer, p_output_tokens integer, p_latency_ms integer, p_status text
) returns void
language plpgsql
security invoker
set search_path = ''
as $$
declare refund bigint;
begin
  p_actual := greatest(0, least(p_actual, p_reserved));
  update public.atlas_inference_usage set charged_micros=p_actual,model=p_model,input_tokens=p_input_tokens,output_tokens=p_output_tokens,
    latency_ms=p_latency_ms,status=p_status,settled_at=now()
    where request_id=p_request_id and user_id=p_user_id and status='reserved';
  if not found then return; end if;
  refund := p_reserved - p_actual;
  if refund > 0 then
    update public.atlas_billing_accounts set balance_micros=balance_micros+refund,updated_at=now() where user_id=p_user_id;
    insert into public.atlas_credit_ledger(user_id,amount_micros,kind,reference) values (p_user_id,refund,'inference_settlement','settle:' || p_request_id);
  end if;
end;
$$;

create or replace function public.atlas_grant_credits(p_user_id uuid, p_amount bigint, p_reference text, p_kind text)
returns void
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if p_amount <= 0 then raise exception 'grant must be positive'; end if;
  insert into public.atlas_credit_ledger(user_id,amount_micros,kind,reference)
    values (p_user_id,p_amount,p_kind,p_reference) on conflict (reference) do nothing;
  if found then
    insert into public.atlas_billing_accounts(user_id,balance_micros) values (p_user_id,p_amount)
      on conflict (user_id) do update set balance_micros=public.atlas_billing_accounts.balance_micros+p_amount,updated_at=now();
  end if;
end;
$$;

revoke all on function public.atlas_reserve_credits(uuid,uuid,bigint) from public, anon, authenticated;
revoke all on function public.atlas_settle_credits(uuid,uuid,bigint,bigint,text,integer,integer,integer,text) from public, anon, authenticated;
revoke all on function public.atlas_grant_credits(uuid,bigint,text,text) from public, anon, authenticated;
grant execute on function public.atlas_reserve_credits(uuid,uuid,bigint) to service_role;
grant execute on function public.atlas_settle_credits(uuid,uuid,bigint,bigint,text,integer,integer,integer,text) to service_role;
grant execute on function public.atlas_grant_credits(uuid,bigint,text,text) to service_role;
