--
--  Drop all tables.
--
DROP TABLE "PA_ACTIVATION" CASCADE CONSTRAINTS;
DROP TABLE "PA_APPLICATION" CASCADE CONSTRAINTS;
DROP TABLE "PA_APPLICATION_VERSION" CASCADE CONSTRAINTS;
DROP TABLE "PA_TOKEN" CASCADE CONSTRAINTS;
DROP TABLE "PA_MASTER_KEYPAIR" CASCADE CONSTRAINTS;
DROP TABLE "PA_SIGNATURE_AUDIT" CASCADE CONSTRAINTS;
DROP TABLE "PA_INTEGRATION" CASCADE CONSTRAINTS;
DROP TABLE "PA_APPLICATION_CALLBACK" CASCADE CONSTRAINTS;

--
--  Drop all sequences.
--
DROP SEQUENCE "PA_APPLICATION_SEQ";
DROP SEQUENCE "PA_APPLICATION_VERSION_SEQ";
DROP SEQUENCE "PA_MASTER_KEYPAIR_SEQ";
DROP SEQUENCE "PA_SIGNATURE_AUDIT_SEQ";
DROP SEQUENCE "PA_ACTIVATION_HISTORY_SEQ";
