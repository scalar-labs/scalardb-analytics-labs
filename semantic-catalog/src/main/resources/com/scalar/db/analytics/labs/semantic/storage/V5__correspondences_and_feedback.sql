-- Usage feedback replaces question URNs: nothing identifies an Input Resource by a question any
-- more, because usage feedback is a new Input Resource at each registration.
--
-- Edge ends keep their columns but now hold each end in its canonical form, a JSON array of the
-- URNs of its elements. Log records written before this change, with join edges and single-element
-- ends, are not converted: no store holding them was released.

DROP INDEX semantic_catalog.input_resources_question_urn;

ALTER TABLE semantic_catalog.input_resources DROP COLUMN question;
