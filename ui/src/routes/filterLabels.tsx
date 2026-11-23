// SPDX-License-Identifier: Apache-2.0
//
// Names of filter values for the shell's chips (FilterChipBar valueLabel, docs/15 §6.5): the f=
// values of some screens are keys (docs/14 §6: f.service=<service key>, f.instance=<instance id>,
// f.error_class=<error class id>), which mean nothing to a reader. The screen that knows the names
// (the XLog screen from its points' dictionary) registers a resolver while it is mounted; the
// shell's UrlFilterChips shows the names, the keys stay in the URL and in the chip's data-value.
// Without a resolver, or for a key the resolver does not know, the chip shows the value itself.
// Two contexts: the screen only registers (a stable function, so registering never re-renders the
// screen), the chips read the resolver.
import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';

import type { FilterValueLabel } from '../components/FilterChipBar';

type Register = (label: FilterValueLabel | null) => void;

const LabelContext = createContext<FilterValueLabel | null>(null);
const RegisterContext = createContext<Register>(() => undefined);

/** Holds the current screen's resolver for the chips (Shell wraps the chips and the screen). */
export function FilterLabelProvider({ children }: { readonly children: ReactNode }) {
  const [label, setLabel] = useState<FilterValueLabel | null>(null);
  // A function is a valid state value only through the updater form.
  const register = useCallback<Register>((next) => {
    setLabel(() => next);
  }, []);
  return (
    <RegisterContext.Provider value={register}>
      <LabelContext.Provider value={label}>{children}</LabelContext.Provider>
    </RegisterContext.Provider>
  );
}

/** The resolver of the current screen, or null. */
export function useFilterValueLabel(): FilterValueLabel | null {
  return useContext(LabelContext);
}

/** Registers `label` for the chips while the calling screen is mounted (null clears it). */
export function useRegisterFilterLabels(label: FilterValueLabel | null): void {
  const register = useContext(RegisterContext);
  useEffect(() => {
    register(label);
    return () => {
      register(null);
    };
  }, [label, register]);
}
