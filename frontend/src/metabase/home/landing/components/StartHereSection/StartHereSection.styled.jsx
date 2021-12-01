import styled from "styled-components";
import { Link } from "react-router";
import { color } from "metabase/lib/colors";
import Icon from "metabase/components/Icon";
import {
  breakpointMinMedium,
  breakpointMinSmall,
} from "metabase/styled-components/theme";

export const ListRoot = styled.div`
  display: grid;
  grid-template-columns: repeat(1, 1fr);
  gap: 1rem;

  ${breakpointMinSmall} {
    grid-template-columns: repeat(2, 1fr);
  }

  ${breakpointMinMedium} {
    grid-template-columns: repeat(3, 1fr);
  }
`;

export const CardRoot = styled(Link)`
  display: block;
  padding: 1.5rem;
  color: ${color("text-dark")};
  border: 1px solid ${color("border")};
  border-radius: 0.5rem;
  background-color: ${color("white")};

  &:hover {
    color: ${color("brand")};
  }
`;

export const CardIcon = styled(Icon)`
  display: block;
  color: ${color("brand")};
  width: 1.5rem;
  height: 1.5rem;
`;

export const CardTitle = styled.span`
  display: block;
  font-weight: 700;
  margin-top: 2.25rem;
`;

export const BannerRoot = styled.div`
  display: flex;
  align-items: center;
  padding: 1.5rem;
  border: 1px solid ${color("border")};
  border-radius: 0.5rem;
  background-color: ${color("white")};
`;

export const BannerIconContainer = styled.div`
  display: flex;
  flex: 0 0 auto;
  justify-content: center;
  align-items: center;
  width: 2.5rem;
  height: 2.5rem;
  border: 1px solid ${color("border")};
  border-radius: 50%;
`;

export const BannerIcon = styled(Icon)`
  display: block;
  color: ${color("brand")};
  width: 1rem;
  height: 1rem;
`;

export const BannerContent = styled.div`
  flex: 1 1 auto;
  margin: 0 1rem;
`;

export const BannerTitle = styled.div`
  color: ${color("text-dark")};
  font-weight: 700;
`;

export const BannerDescription = styled.div`
  color: ${color("text-medium")};
  margin-top: 0.5rem;
`;
