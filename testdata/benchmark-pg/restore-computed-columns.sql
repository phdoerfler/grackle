-- testdata/benchmark-pg/restore-computed-columns.sql
--
-- sales.customer.accountnumber and sales.salesorderheader.salesordernumber were
-- SQL Server *computed* columns. lorint's AdventureWorks-for-Postgres port
-- (install.sql) creates them only long enough to accept the CSV import, then
-- drops them:
--
--   -- Calculated columns that needed to be there just for the CSV import
--   ALTER TABLE Sales.Customer DROP COLUMN AccountNumber;
--   ALTER TABLE Sales.SalesOrderDetail DROP COLUMN LineTotal;
--   ALTER TABLE Sales.SalesOrderHeader DROP COLUMN SalesOrderNumber;
--
-- The Grackle benchmark mapping maps both columns (to match the original SQL
-- Server dataset), so they are restored here, after install.sql has run,
-- populated with the exact values SQL Server computed for them:
--
--   Sales.Customer.AccountNumber          AS ISNULL('AW' + dbo.ufnLeadingZeros(CustomerID), '')
--     ufnLeadingZeros zero-pads CustomerID to 8 digits, e.g. customer 1 -> AW00000001.
--   Sales.SalesOrderHeader.SalesOrderNumber AS ISNULL(N'SO' + CONVERT(nvarchar(23), SalesOrderID), N'*** ERROR ***')
--     e.g. sales order 43659 -> SO43659.
--
-- Postgres 11.8 (this image's base) predates GENERATED ALWAYS AS ... STORED,
-- so these are added as plain columns and backfilled with an UPDATE rather
-- than computed in-place. sales.salesorderdetail.linetotal is intentionally
-- left dropped -- nothing in the benchmark mapping references it.

ALTER TABLE sales.customer ADD COLUMN accountnumber varchar;
UPDATE sales.customer SET accountnumber = 'AW' || lpad(customerid::text, 8, '0');

ALTER TABLE sales.salesorderheader ADD COLUMN salesordernumber varchar(23);
UPDATE sales.salesorderheader SET salesordernumber = 'SO' || salesorderid::text;
