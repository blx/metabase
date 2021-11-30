import React from "react";
import PropTypes from "prop-types";
import { t } from "ttag";
import * as Urls from "metabase/lib/urls";
import Button from "metabase/components/Button";
import Tooltip from "metabase/components/Tooltip";
import ModalWithTrigger from "metabase/components/ModalWithTrigger";
import Section, {
  SectionHeader,
  SectionIcon,
  SectionTitle,
} from "../LandingSection";
import {
  DatabaseCardRoot,
  DatabaseGrid,
  DatabaseIcon,
  DatabaseTitle,
} from "./OutDataSection.styled";

const propTypes = {
  databases: PropTypes.array.isRequired,
  onRemoveSection: PropTypes.func,
};

const OurDataSection = ({ databases, onRemoveSection }) => {
  return (
    <Section>
      <SectionHeader>
        <SectionTitle>{t`Our data`}</SectionTitle>
        <SectionRemoveModal onRemoveSection={onRemoveSection}>
          <Tooltip tooltip={t`Hide this section`}>
            <SectionIcon name="close" />
          </Tooltip>
        </SectionRemoveModal>
      </SectionHeader>
      <DatabaseGrid>
        {databases.map(database => (
          <DatabaseCard
            key={database.id}
            title={database.name}
            link={Urls.browseDatabase(database)}
            isActive={true}
          />
        ))}
        <DatabaseCard
          title={t`Add a database`}
          link={Urls.newDatabase()}
          isActive={false}
        />
      </DatabaseGrid>
    </Section>
  );
};

OurDataSection.propTypes = propTypes;

const cardPropTypes = {
  title: PropTypes.string.isRequired,
  link: PropTypes.string.isRequired,
  isActive: PropTypes.bool,
};

const DatabaseCard = ({ title, link, isActive }) => {
  return (
    <DatabaseCardRoot to={link} isActive={isActive}>
      <DatabaseIcon name="database" isActive={isActive} />
      <DatabaseTitle isActive={isActive}>{title}</DatabaseTitle>
    </DatabaseCardRoot>
  );
};

DatabaseCard.propTypes = cardPropTypes;

const modalPropTypes = {
  children: PropTypes.node,
  onRemoveSection: PropTypes.func,
};

const SectionRemoveModal = ({ children, onRemoveSection }) => {
  return (
    <ModalWithTrigger
      title={t`Remove this section?`}
      footer={<Button danger onClick={onRemoveSection}>{t`Remove`}</Button>}
      triggerElement={children}
    >
      <span>
        {t`"Our Data" won’t show up on the homepage for any of your users anymore, but you can always browse through your databases and tables by clicking Browse Data in the main navigation.`}
      </span>
    </ModalWithTrigger>
  );
};

SectionRemoveModal.propTypes = modalPropTypes;

export default OurDataSection;
