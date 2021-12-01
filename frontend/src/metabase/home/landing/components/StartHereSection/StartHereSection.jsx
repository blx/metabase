import React from "react";
import PropTypes from "prop-types";
import { jt, t } from "ttag";
import Settings from "metabase/lib/settings";
import * as Urls from "metabase/lib/urls";
import { ROOT_COLLECTION } from "metabase/entities/collections";
import Link from "metabase/components/Link";
import ExternalLink from "metabase/components/ExternalLink";
import Section, { SectionHeader, SectionTitle } from "../LandingSection";
import {
  BannerContent,
  BannerDescription,
  BannerIcon,
  BannerIconContainer,
  BannerRoot,
  BannerTitle,
} from "./StartHereSection.styled";

const propTypes = {
  isAdmin: PropTypes.bool,
  onRemoveSection: PropTypes.func,
};

const StartHereSection = () => {
  return (
    <Section>
      <SectionHeader>
        <SectionTitle>{t`Start here`}</SectionTitle>
      </SectionHeader>
      <DashboardBanner />
    </Section>
  );
};

StartHereSection.propTypes = propTypes;

const DatabaseBanner = () => {
  const userUrl = Urls.newUser();
  const databaseUrl = Urls.newDatabase();
  const docsUrl = Settings.docsUrl("getting-started");

  return (
    <BannerRoot>
      <BannerIconContainer>
        <BannerIcon name="database" />
      </BannerIconContainer>
      <BannerContent>
        <BannerTitle>{t`Connect your data to get the most out of Metabase`}</BannerTitle>
        <BannerDescription>
          {jt`If you need help, you can ${(
            <ExternalLink href={userUrl}>{t`invite a teammate`}</ExternalLink>
          )} or ${(
            <ExternalLink href={docsUrl}>
              {t`check out our set up guides`}
            </ExternalLink>
          )}.`}
        </BannerDescription>
      </BannerContent>
      <Link
        className="Button Button--primary"
        to={databaseUrl}
      >{t`Add my data`}</Link>
    </BannerRoot>
  );
};

const DashboardBanner = () => {
  const collectionUrl = Urls.collection(ROOT_COLLECTION);

  return (
    <BannerRoot>
      <BannerIconContainer>
        <BannerIcon name="pin" />
      </BannerIconContainer>
      <BannerContent>
        <BannerTitle>{t`Your teams’ most important dashboards go here`}</BannerTitle>
        <BannerDescription>{jt`Pin dashboards in ${(
          <ExternalLink href={collectionUrl}>
            {ROOT_COLLECTION.name}
          </ExternalLink>
        )} to have them appear in this space for everyone.`}</BannerDescription>
      </BannerContent>
    </BannerRoot>
  );
};

export default StartHereSection;
