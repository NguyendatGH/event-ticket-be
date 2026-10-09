-- Tài khoản nhận tiền đã lên gateway (settlement của merchant) hay chưa.
-- NULL = chưa đẩy, hoặc BTC vừa đổi tài khoản: GatewayProvisioningJob sẽ đẩy lại.
-- Dòng cũ (có từ trước khi gateway biết settlement) đều NULL, nên job tự đồng bộ chúng, không cần script tay.
alter table organizer_bank_accounts add column gateway_synced_at timestamptz;

create index ix_organizer_bank_accounts_unsynced on organizer_bank_accounts (organizer_id)
    where is_default and gateway_synced_at is null;
