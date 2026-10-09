--
-- PostgreSQL database dump
--

\restrict hZS3W9oqy5bK5uuXDhV7zFzRauNmnlPnyzYO65aYvr4O8wLeRcCqjd5bXE7oC0j

-- Dumped from database version 17.11
-- Dumped by pg_dump version 17.11

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: SCHEMA public; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON SCHEMA public IS 'Corporate catering application schema';


--
-- Name: reject_order_status_history_mutation(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.reject_order_status_history_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'order_status_history is append-only';
END;
$$;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: category; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.category (
    id uuid NOT NULL,
    name character varying(100) NOT NULL,
    CONSTRAINT ck_category_name_not_blank CHECK ((btrim((name)::text) <> ''::text))
);


--
-- Name: corporate_order; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.corporate_order (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    delivery_point_id uuid NOT NULL,
    requested_delivery_at timestamp with time zone NOT NULL,
    status character varying(32) NOT NULL,
    total_amount numeric(19,2) DEFAULT 0 NOT NULL,
    comment character varying(1000),
    created_at timestamp with time zone NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_corporate_order_status CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'SUBMITTED'::character varying, 'CONFIRMED'::character varying, 'REJECTED'::character varying, 'CANCELLED'::character varying, 'IN_COOKING'::character varying, 'READY'::character varying, 'COMPLETED'::character varying])::text[]))),
    CONSTRAINT ck_corporate_order_total_non_negative CHECK ((total_amount >= (0)::numeric))
);


--
-- Name: delivery_point; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.delivery_point (
    id uuid NOT NULL,
    organization_id uuid NOT NULL,
    name character varying(200) NOT NULL,
    address character varying(500) NOT NULL,
    contact_name character varying(200) NOT NULL,
    contact_phone character varying(32) NOT NULL,
    active boolean DEFAULT true NOT NULL,
    CONSTRAINT ck_delivery_point_address_not_blank CHECK ((btrim((address)::text) <> ''::text)),
    CONSTRAINT ck_delivery_point_contact_name_not_blank CHECK ((btrim((contact_name)::text) <> ''::text)),
    CONSTRAINT ck_delivery_point_name_not_blank CHECK ((btrim((name)::text) <> ''::text)),
    CONSTRAINT ck_delivery_point_phone_format CHECK (((contact_phone)::text ~ '^\+[1-9][0-9]{7,14}$'::text))
);


--
-- Name: dish; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dish (
    id uuid NOT NULL,
    name character varying(200) NOT NULL,
    description character varying(2000) DEFAULT ''::character varying NOT NULL,
    current_price numeric(19,2) NOT NULL,
    active boolean DEFAULT true NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_dish_current_price_positive CHECK ((current_price > (0)::numeric)),
    CONSTRAINT ck_dish_name_not_blank CHECK ((btrim((name)::text) <> ''::text))
);


--
-- Name: dish_category; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.dish_category (
    dish_id uuid NOT NULL,
    category_id uuid NOT NULL
);


--
-- Name: flyway_schema_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.flyway_schema_history (
    installed_rank integer NOT NULL,
    version character varying(50),
    description character varying(200) NOT NULL,
    type character varying(20) NOT NULL,
    script character varying(1000) NOT NULL,
    checksum integer,
    installed_by character varying(100) NOT NULL,
    installed_on timestamp without time zone DEFAULT now() NOT NULL,
    execution_time integer NOT NULL,
    success boolean NOT NULL
);


--
-- Name: order_line; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.order_line (
    id uuid NOT NULL,
    order_id uuid NOT NULL,
    dish_id uuid NOT NULL,
    dish_name_snapshot character varying(200),
    quantity integer NOT NULL,
    unit_price_snapshot numeric(19,2),
    CONSTRAINT ck_order_line_price_snapshot CHECK (((unit_price_snapshot IS NULL) OR (unit_price_snapshot > (0)::numeric))),
    CONSTRAINT ck_order_line_quantity CHECK (((quantity >= 1) AND (quantity <= 100000))),
    CONSTRAINT ck_order_line_snapshots_together CHECK (((dish_name_snapshot IS NULL) = (unit_price_snapshot IS NULL)))
);


--
-- Name: order_status_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.order_status_history (
    id uuid NOT NULL,
    order_id uuid NOT NULL,
    from_status character varying(32) NOT NULL,
    to_status character varying(32) NOT NULL,
    reason character varying(500),
    changed_by uuid,
    changed_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_order_status_history_from CHECK (((from_status)::text = ANY ((ARRAY['DRAFT'::character varying, 'SUBMITTED'::character varying, 'CONFIRMED'::character varying, 'REJECTED'::character varying, 'CANCELLED'::character varying, 'IN_COOKING'::character varying, 'READY'::character varying, 'COMPLETED'::character varying])::text[]))),
    CONSTRAINT ck_order_status_history_reason CHECK (((((to_status)::text = ANY ((ARRAY['REJECTED'::character varying, 'CANCELLED'::character varying])::text[])) AND (reason IS NOT NULL) AND (btrim((reason)::text) <> ''::text)) OR (((to_status)::text <> ALL ((ARRAY['REJECTED'::character varying, 'CANCELLED'::character varying])::text[])) AND (reason IS NULL)))),
    CONSTRAINT ck_order_status_history_to CHECK (((to_status)::text = ANY ((ARRAY['DRAFT'::character varying, 'SUBMITTED'::character varying, 'CONFIRMED'::character varying, 'REJECTED'::character varying, 'CANCELLED'::character varying, 'IN_COOKING'::character varying, 'READY'::character varying, 'COMPLETED'::character varying])::text[]))),
    CONSTRAINT ck_order_status_history_transition CHECK (((((from_status)::text = 'DRAFT'::text) AND ((to_status)::text = ANY ((ARRAY['SUBMITTED'::character varying, 'CANCELLED'::character varying])::text[]))) OR (((from_status)::text = 'SUBMITTED'::text) AND ((to_status)::text = ANY ((ARRAY['CONFIRMED'::character varying, 'REJECTED'::character varying, 'CANCELLED'::character varying])::text[]))) OR (((from_status)::text = 'CONFIRMED'::text) AND ((to_status)::text = ANY ((ARRAY['IN_COOKING'::character varying, 'CANCELLED'::character varying])::text[]))) OR (((from_status)::text = 'IN_COOKING'::text) AND ((to_status)::text = 'READY'::text)) OR (((from_status)::text = 'READY'::text) AND ((to_status)::text = 'COMPLETED'::text))))
);


--
-- Name: organization; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.organization (
    id uuid NOT NULL,
    name character varying(200) NOT NULL,
    phone character varying(32) NOT NULL,
    active boolean DEFAULT true NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_organization_name_not_blank CHECK ((btrim((name)::text) <> ''::text)),
    CONSTRAINT ck_organization_phone_format CHECK (((phone)::text ~ '^\+[1-9][0-9]{7,14}$'::text))
);


--
-- Name: category category_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.category
    ADD CONSTRAINT category_pkey PRIMARY KEY (id);


--
-- Name: corporate_order corporate_order_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.corporate_order
    ADD CONSTRAINT corporate_order_pkey PRIMARY KEY (id);


--
-- Name: delivery_point delivery_point_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.delivery_point
    ADD CONSTRAINT delivery_point_pkey PRIMARY KEY (id);


--
-- Name: dish_category dish_category_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dish_category
    ADD CONSTRAINT dish_category_pkey PRIMARY KEY (dish_id, category_id);


--
-- Name: dish dish_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dish
    ADD CONSTRAINT dish_pkey PRIMARY KEY (id);


--
-- Name: flyway_schema_history flyway_schema_history_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.flyway_schema_history
    ADD CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank);


--
-- Name: order_line order_line_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_line
    ADD CONSTRAINT order_line_pkey PRIMARY KEY (id);


--
-- Name: order_status_history order_status_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_status_history
    ADD CONSTRAINT order_status_history_pkey PRIMARY KEY (id);


--
-- Name: organization organization_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.organization
    ADD CONSTRAINT organization_pkey PRIMARY KEY (id);


--
-- Name: category uq_category_name; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.category
    ADD CONSTRAINT uq_category_name UNIQUE (name);


--
-- Name: delivery_point uq_delivery_point_organization_name; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.delivery_point
    ADD CONSTRAINT uq_delivery_point_organization_name UNIQUE (organization_id, name);


--
-- Name: order_line uq_order_line_order_dish; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_line
    ADD CONSTRAINT uq_order_line_order_dish UNIQUE (order_id, dish_id);


--
-- Name: flyway_schema_history_s_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX flyway_schema_history_s_idx ON public.flyway_schema_history USING btree (success);


--
-- Name: idx_corporate_order_delivery_point; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_corporate_order_delivery_point ON public.corporate_order USING btree (delivery_point_id);


--
-- Name: idx_corporate_order_organization_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_corporate_order_organization_created ON public.corporate_order USING btree (organization_id, created_at DESC, id DESC);


--
-- Name: idx_corporate_order_status_delivery; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_corporate_order_status_delivery ON public.corporate_order USING btree (status, requested_delivery_at, id);


--
-- Name: idx_dish_active_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dish_active_id ON public.dish USING btree (active, id);


--
-- Name: idx_dish_category_category_dish; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_dish_category_category_dish ON public.dish_category USING btree (category_id, dish_id);


--
-- Name: idx_order_line_order; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_order_line_order ON public.order_line USING btree (order_id);


--
-- Name: idx_order_status_history_order_changed; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_order_status_history_order_changed ON public.order_status_history USING btree (order_id, changed_at, id);


--
-- Name: order_status_history trg_order_status_history_append_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_order_status_history_append_only BEFORE DELETE OR UPDATE ON public.order_status_history FOR EACH ROW EXECUTE FUNCTION public.reject_order_status_history_mutation();


--
-- Name: corporate_order fk_corporate_order_delivery_point; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.corporate_order
    ADD CONSTRAINT fk_corporate_order_delivery_point FOREIGN KEY (delivery_point_id) REFERENCES public.delivery_point(id) ON DELETE RESTRICT;


--
-- Name: corporate_order fk_corporate_order_organization; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.corporate_order
    ADD CONSTRAINT fk_corporate_order_organization FOREIGN KEY (organization_id) REFERENCES public.organization(id) ON DELETE RESTRICT;


--
-- Name: delivery_point fk_delivery_point_organization; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.delivery_point
    ADD CONSTRAINT fk_delivery_point_organization FOREIGN KEY (organization_id) REFERENCES public.organization(id) ON DELETE RESTRICT;


--
-- Name: dish_category fk_dish_category_category; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dish_category
    ADD CONSTRAINT fk_dish_category_category FOREIGN KEY (category_id) REFERENCES public.category(id) ON DELETE RESTRICT;


--
-- Name: dish_category fk_dish_category_dish; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.dish_category
    ADD CONSTRAINT fk_dish_category_dish FOREIGN KEY (dish_id) REFERENCES public.dish(id) ON DELETE RESTRICT;


--
-- Name: order_line fk_order_line_dish; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_line
    ADD CONSTRAINT fk_order_line_dish FOREIGN KEY (dish_id) REFERENCES public.dish(id) ON DELETE RESTRICT;


--
-- Name: order_line fk_order_line_order; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_line
    ADD CONSTRAINT fk_order_line_order FOREIGN KEY (order_id) REFERENCES public.corporate_order(id) ON DELETE RESTRICT;


--
-- Name: order_status_history fk_order_status_history_order; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.order_status_history
    ADD CONSTRAINT fk_order_status_history_order FOREIGN KEY (order_id) REFERENCES public.corporate_order(id) ON DELETE RESTRICT;


--
-- PostgreSQL database dump complete
--

\unrestrict hZS3W9oqy5bK5uuXDhV7zFzRauNmnlPnyzYO65aYvr4O8wLeRcCqjd5bXE7oC0j
